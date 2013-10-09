#!/usr/bin/env python
# encoding: utf-8
from __future__ import with_statement

import sys
import os
import re
import optparse
import logging
import logging.handlers
import logging.config
import signal
import atexit
import httplib
import socket
import time
import platform
from base64 import b64encode
from collections import defaultdict
from contextlib import closing
from functools import wraps

try:
    import java_urllib2 as urllib2
except ImportError:
    import urllib2

try:
    import com.xhaus.jyson.JysonCodec as json
except ImportError:
    try:
        import json
    except ImportError:
        import simplejson as json  # Python 2.5 dependency

try:
    from java.lang import InterruptedException
except ImportError:
    class InterruptedException(Exception):
        pass

try:
    # Apparently native Java exceptions are being raised with a different
    # baseclass other than the standard Python 'Exception' baseclass. This
    # means catch-all except blocks wlil not work unless they except for both
    # Exception *and* JavaException
    from java.lang import Exception as JavaException
except ImportError:
    class JavaException(Exception):
        pass

RETRY_PROVISION_MAX = 4
RETRY_BOOT_MAX = 4
RETRY_REST_WAIT = 5
RETRY_REST_MAX = 6
REST_POLL_WAIT = 3
RETRY_SSH_MAX = 4
HEALTH_CHECK_INTERVAL = 15
HEALTH_CHECK_FAIL = 5 * 60  # no good check after this amount of time == fail
LATENCY_LOG = 150  # log when making connections takes this many ms
LATENCY_WARNING = 350  # warn when making connections takes this many ms
SIGNALS_RECV_MAX = 4  # used with --allow-unclean-exit

is_windows = platform.system().lower() == "windows"
logger = logging.getLogger(__name__)
fileout = None
dying = False


class DeleteRequest(urllib2.Request):

    def get_method(self):
        return "DELETE"


class HTTPResponseError(Exception):

    def __init__(self, msg):
        self.msg = msg

    def __str__(self):
        return "HTTP server responded with '%s' (expected 'OK')" % self.msg


class ShutdownRequestedError(Exception):
    pass


class TunnelMachineError(Exception):
    pass


class TunnelMachineProvisionError(TunnelMachineError):
    pass


class TunnelMachineBootError(TunnelMachineError):
    pass


class TunnelMachine(object):

    _host_search = re.compile("//([^/]+)").search

    def __init__(self, rest_url, user, password,
                 domains, ssh_port, boost_mode, use_ssh,
                 fast_fail_regexps, direct_domains, no_ssl_bump_domains, shared_tunnel,
                 tunnel_identifier, vm_version, squid_opts, metadata=None):
        self.user = user
        self.password = password
        self.domains = set(domains) if domains else set()
        self.ssh_port = ssh_port
        self.boost_mode = boost_mode
        self.use_ssh = use_ssh
        self.fast_fail_regexps = fast_fail_regexps
        self.direct_domains = direct_domains
        self.no_ssl_bump_domains = no_ssl_bump_domains
        self.shared_tunnel = shared_tunnel
        self.tunnel_identifier = tunnel_identifier
        self.vm_version = vm_version
        self.squid_opts = squid_opts
        self.metadata = metadata or dict()

        self.reverse_ssh = None
        self.is_shutdown = False
        self.base_url = "%(rest_url)s/%(user)s/tunnels" % locals()
        self.rest_host = self._host_search(rest_url).group(1)
        self.basic_auth_header = {"Authorization": "Basic %s"
                                  % b64encode("%s:%s" % (user, password))}

        self._set_urlopen(user, password)

        for attempt in xrange(1, RETRY_PROVISION_MAX):
            try:
                self._provision_tunnel()
                break
            except TunnelMachineProvisionError, e:
                logger.warning(e)
                if attempt == RETRY_PROVISION_MAX:
                    raise TunnelMachineError(
                        "!! Could not provision tunnel remote VM. Please contact "
                        "help@saucelabs.com.")

    def _set_urlopen(self, user, password):
        # always send Basic Auth header (HTTPBasicAuthHandler was unreliable)
        opener = urllib2.build_opener()
        opener.addheaders = self.basic_auth_header.items()
        self.urlopen = opener.open

    # decorator
    def _retry_rest_api(f):
        @wraps(f)
        def wrapper(*args, **kwargs):
            previous_failed = False
            for attempt in xrange(1, RETRY_REST_MAX + 1):
                try:
                    result = f(*args, **kwargs)
                    if previous_failed:
                        logger.info("Connection succeeded")
                    return result
                except (HTTPResponseError,
                        urllib2.URLError, httplib.HTTPException,
                        socket.gaierror, socket.error), e:
                    logger.warning("Problem connecting to Sauce Labs REST API "
                                   "(%s)", str(e))
                    if attempt == RETRY_REST_MAX:
                        raise TunnelMachineError(
                            "Could not reach Sauce Labs REST API after %d "
                            "tries. Is your network down or firewalled?"
                            % attempt)
                    previous_failed = True
                    logger.debug("Retrying in %ds", RETRY_REST_WAIT)
                    time.sleep(RETRY_REST_WAIT)
                except Exception, e:
                    raise TunnelMachineError(
                        "An error occurred while contacting Sauce Labs REST "
                        "API (%s). Please contact help@saucelabs.com." % str(e))
        return wrapper

    @_retry_rest_api
    def _get_doc(self, url_or_req):
        try:
            with closing(self.urlopen(url_or_req)) as resp:
                if resp.msg != "OK":
                    raise HTTPResponseError(resp.msg)
                return json.loads(resp.read())
        except JavaException, ex:
            # Running under Jython, JavaException is raised instead of
            # HTTP Exceptions
            raise HTTPResponseError(unicode(ex))

    def _provision_tunnel(self):
        # Shutdown any tunnel using a requested domain
        kill_list = set()
        for doc in self._get_doc(self.base_url + "?full=1"):
            if ((doc.get('domain_names') and
                 set(doc['domain_names']) & self.domains) or
                (doc.get('tunnel_identifier') and
                 doc['tunnel_identifier'] == self.tunnel_identifier)):
                kill_list.add(doc['id'])
        if kill_list:
            logger.warning("NOTICE: Already running tunnels exist that would collide"
                           " with this instance of Connect."
                           " Shutting them down before starting.\n"
                           "Read http://saucelabs.com/docs/connect#tunnel-identifier"
                           " for more details.")
            if self.domains:
                message = "without any identifiers"
            elif self.tunnel_identifier:
                message = "under the same identifier"
            for tunnel_id in kill_list:
                for attempt in xrange(1, 4):  # try a few times, then bail
                    logger.info("Shutting down tunnel VM: %s running %s",
                                tunnel_id, message)
                    url = "%s/%s" % (self.base_url, tunnel_id)
                    doc = self._get_doc(DeleteRequest(url=url))
                    if (not doc.get('result') or
                        not doc.get('id') == tunnel_id):
                        logger.warning("Old tunnel remote VM failed to shut down."
                                       " Status: %s", doc)
                        continue
                    doc = self._get_doc(url)
                    while doc.get('status') not in ["halting", "terminated"]:
                        logger.debug(
                            "Waiting for old tunnel remote VM to start halting")
                        time.sleep(REST_POLL_WAIT)
                        doc = self._get_doc(url)
                    break

        # Request a tunnel machine
        headers = {"Content-Type": "application/json"}
        data = json.dumps(dict(domain_names=list(self.domains),
                               metadata=self.metadata,
                               ssh_port=self.ssh_port,
                               use_caching_proxy=self.boost_mode,
                               use_kgp=not self.use_ssh,
                               fast_fail_regexps=(self.fast_fail_regexps.split(',')
                                                  if self.fast_fail_regexps else None),
                               direct_domains=(self.direct_domains.split(',')
                                               if self.direct_domains else None),
                               no_ssl_bump_domains=(self.no_ssl_bump_domains.split(',')
                                               if self.no_ssl_bump_domains else None),
                               shared_tunnel=self.shared_tunnel,
                               tunnel_identifier=self.tunnel_identifier,
                               vm_version=self.vm_version,
                               squid_config=(self.squid_opts.split(',')
                                             if self.squid_opts else None)))
        logger.info("%s" % data)
        req = urllib2.Request(url=self.base_url, headers=headers, data=data)
        doc = self._get_doc(req)
        if doc.get('error'):
            raise TunnelMachineProvisionError(doc['error'])
        for key in ['id']:
            if not doc.get(key):
                raise TunnelMachineProvisionError(
                    "Document for provisioned tunnel remote VM is missing the key "
                    "or value for '%s'" % key)
        self.id = doc['id']
        self.url = "%s/%s" % (self.base_url, self.id)
        logger.info("Tunnel remote VM is provisioned (%s)" % self.id)

    def ready_wait(self):
        """Wait for the machine to reach the 'running' state."""
        previous_status = None
        while True:
            doc = self._get_doc(self.url)
            status = doc.get('status')
            if status == "running":
                break
            if status in ["halting", "terminated"]:
                if doc.get('user_shut_down'):
                    raise ShutdownRequestedError("Tunnel shut down by userJ"
                                                 " (or another Sauce Connect"
                                                 " process), quitting")
                else:
                    raise TunnelMachineBootError("Tunnel remote VM was shut down")
            if status != previous_status:
                logger.info("Tunnel remote VM is %s .." % status)
            previous_status = status
            time.sleep(REST_POLL_WAIT)
        self.host = doc['host']
        logger.info("Tunnel remote VM is running at %s" % self.host)

    def shutdown(self):
        if self.is_shutdown:
            return

        if self.reverse_ssh:
            self.reverse_ssh.stop()

        logger.info("Shutting down tunnel remote VM (please wait)")
        logger.debug("tunnel remote VM ID: %s" % self.id)

        try:
            doc = self._get_doc(DeleteRequest(url=self.url))
        except TunnelMachineError, e:
            logger.warning("Unable to shut down tunnel remote VM")
            logger.debug("Shut down failed because: %s", str(e))
            self.is_shutdown = True  # fuhgeddaboudit
            return
        #assert doc.get('ok')
        assert doc.get('id') == self.id

        previous_status = None
        while True:
            doc = self._get_doc(self.url)
            status = doc.get('status')
            if status == "terminated":
                break
            if status != previous_status:
                logger.info("Tunnel remote VM is %s .." % status)
            previous_status = status
            time.sleep(REST_POLL_WAIT)
        logger.info("Finished shutting down tunnel remote VM")
        self.is_shutdown = True

    # Make us usable with contextlib.closing
    close = shutdown

    def check_running(self):
        doc = self._get_doc(self.url)
        if doc.get('status') == "running":
            return
        raise TunnelMachineError(
            "The tunnel remote VM is no longer running. It may have been shutdown "
            "via the website or by another Sauce Connect script requesting these "
            "domains: %s" % list(self.domains))


class HealthCheckFail(Exception):
    pass


class HealthChecker(object):

    latency_log = LATENCY_LOG

    def __init__(self, host, ports, fail_msg=None):
        """fail_msg can include '%(host)s' and '%(port)d'"""
        self.host = host
        self.fail_msg = fail_msg
        if not self.fail_msg:
            self.fail_msg = ("!! Your tests will fail while your network "
                             "can not get to %(host)s:%(port)d.")
        self.ports = frozenset(int(p) for p in ports)
        self.last_tcp_connect = defaultdict(time.time)
        self.last_tcp_ping = defaultdict(lambda: None)

    def _tcp_ping(self, port):
        with closing(socket.socket()) as sock:
            start_time = time.time()
            try:
                sock.connect((self.host, port))
                return int(1000 * (time.time() - start_time))
            except (socket.gaierror, socket.error), e:
                logger.warning("Could not connect to %s:%s (%s)",
                               self.host, port, str(e))

    def check(self):
        now = time.time()
        all_good = True
        for port in self.ports:
            ping_time = self._tcp_ping(port)
            if ping_time is not None:
                # TCP connection succeeded
                self.last_tcp_connect[port] = now
                result = (self.host, port, ping_time)

                if ping_time >= self.latency_log:
                    logger.debug("Connected to local server %s:%s in in %dms" % result)

                if ping_time >= LATENCY_WARNING:
                    if (self.last_tcp_ping[port] is None
                        or self.last_tcp_ping[port] < LATENCY_WARNING):
                        logger.warn("High latency to local server %s:%s (took %dms to "
                                    "connect); tests may run slowly" % result)

                if (ping_time < (LATENCY_WARNING / 2)
                    and self.last_tcp_ping[port]
                    and self.last_tcp_ping[port] >= LATENCY_WARNING):
                    logger.info("Latency to local server %s:%s has lowered (took %dms to "
                                "connect)" % result)

                if self.last_tcp_ping[port] is None:
                    logger.info("Succesfully connected to local server %s:%s in %dms"
                                % result)

                self.last_tcp_ping[port] = ping_time
                continue

            # TCP connection failed
            all_good = False
            self.last_tcp_ping[port] = ping_time
            logger.warning(self.fail_msg % dict(host=self.host, port=port))
            if now - self.last_tcp_connect[port] > HEALTH_CHECK_FAIL:
                raise HealthCheckFail(
                    "Could not connect to local server %s:%s for over %s seconds"
                    % (self.host, port, HEALTH_CHECK_FAIL))
        return all_good


class ReverseSSHError(Exception):
    pass


def peace_out(tunnel=None, returncode=0, atexit=False):
    """Shutdown the tunnel because we're exiting."""
    global dying
    if dying:
        return
    dying = True
    if tunnel:
        tunnel.shutdown()
    if not atexit:
        logger.info("\ Exiting /")
    else:
        logger.info("\ Finished /")


def setup_signal_handler(tunnel, options):
    signal_count = defaultdict(int)
    signal_name = {}

    def sig_handler(signum, frame):
        if options.allow_unclean_exit:
            signal_count[signum] += 1
            if signal_count[signum] > SIGNALS_RECV_MAX:
                logger.info(
                    "Received %s too many times (%d). Making unclean "
                    "exit now!", signal_name[signum], signal_count[signum])
                raise SystemExit(1)
        logger.info("Received signal %s", signal_name[signum])
        peace_out(tunnel)
        raise SystemExit(0)

    # TODO: ?? remove SIGTERM when we implement tunnel leases
    if is_windows:
        supported_signals = ["SIGABRT", "SIGBREAK", "SIGINT", "SIGTERM"]
    else:
        supported_signals = ["SIGHUP", "SIGINT", "SIGQUIT", "SIGTERM"]
    for sig in supported_signals:
        signum = getattr(signal, sig)
        signal_name[signum] = sig
        signal.signal(signum, sig_handler)


def setup_logging(logfile=None, quiet=False, logfilesize=31457280):
    global fileout
    logger.setLevel(logging.DEBUG)

    if not quiet:
        stdout = logging.StreamHandler(sys.stdout)
        stdout.setLevel(logging.INFO)
        stdout.setFormatter(logging.Formatter("%(asctime)s - %(message)s"))
        logger.addHandler(stdout)

    if logfile:
        if not quiet:
            print "* Debug messages will be sent to %s" % logfile
        fileout = logging.handlers.RotatingFileHandler(
            filename=logfile, maxBytes=logfilesize, backupCount=8)
        fileout.setLevel(logging.DEBUG)
        fileout.setFormatter(logging.Formatter(
            "%(asctime)s - %(name)s:%(lineno)d - %(levelname)s - %(message)s"))
        logger.addHandler(fileout)


def get_options(arglist=sys.argv[1:]):
    logfile = "sauce_connect.log"
    logfilesize="31457280"

    op = optparse.OptionParser(usage="", version="sauce_connect")
    op.add_option("-u", "--user", "--username",
                  help="Your Sauce Labs account name.")
    op.add_option("-k", "--api-key",
                  help="On your account at https://saucelabs.com/account")
    op.add_option("-s", "--host", default="localhost",
                  help="Host to forward requests to. [%default]")
    op.add_option("-p", "--port", metavar="PORT",
                  action="append", dest="ports", default=[],
                  help="Forward to this port on HOST. Can be specified "
                       "multiple times. []")
    op.add_option("-d", "--domain", action="append", dest="domains",
                  help="Repeat for each domain you want to forward requests for. "
                  "Example: -d example.test -d '*.example.test'")
    op.add_option("-q", "--quiet", action="store_true", default=False,
                  help="Minimize standard output (see %s)" % logfile)

    og = optparse.OptionGroup(op, "Advanced options")
    og.add_option("-t", "--tunnel-port", metavar="TUNNEL_PORT",
                  action="append", dest="tunnel_ports", default=[],
                  help="The port your tests expect to hit when they run."
                  " By default, we use the same ports as the HOST."
                  " If you know for sure _all_ your tests use something like"
                  " http://site.test:8080/ then set this 8080.")
    og.add_option("--logfile", default=logfile,
                  help="Path of the logfile to write to. [%default]")
    og.add_option("--logfilesize", default=logfilesize,
                  help="Size of the logfile to write to. [%default]")
    og.add_option("--readyfile",
                  help="Path of the file to drop when the tunnel is ready "
                       "for tests to run. By default, no file is dropped.")
    og.add_option("--use-ssh-config", action="store_true", default=False,
                  help="Use the local SSH config. WARNING: Turning this on "
                       "may break the script!")
    og.add_option("--rest-url", default="https://saucelabs.com/rest/v1",
                  help=optparse.SUPPRESS_HELP)
    og.add_option("--allow-unclean-exit", action="store_true", default=False,
                  help=optparse.SUPPRESS_HELP)
    og.add_option("--ssh-port", default=22, type="int",
                  help=optparse.SUPPRESS_HELP)
    og.add_option("-b", "--boost-mode", default=False, action="store_true",
                  help=optparse.SUPPRESS_HELP)
    og.add_option("--ssh", default=False, action="store_true",
                  help=optparse.SUPPRESS_HELP)
    og.add_option("--se-port", default="4445", type="str",
                  help=optparse.SUPPRESS_HELP)
    og.add_option("--fast-fail-regexps", default="", type="str",
                  help=optparse.SUPPRESS_HELP)
    og.add_option("--direct-domains", default="", type="str",
                  help=optparse.SUPPRESS_HELP)
    og.add_option("--no-ssl-bump-domains", default="", type="str",
                  help=optparse.SUPPRESS_HELP)
    og.add_option("--shared-tunnel", default=False, action="store_true",
                  help=optparse.SUPPRESS_HELP)
    og.add_option("--tunnel-identifier", default="", type="str",
                  help=optparse.SUPPRESS_HELP)
    og.add_option("--vm-version", default="", type="str",
                  help=optparse.SUPPRESS_HELP)
    og.add_option("--squid-opts", default="", type="str",
                  help=optparse.SUPPRESS_HELP)
    op.add_option_group(og)

    og = optparse.OptionGroup(op, "Script debugging options")
    og.add_option("--debug-ssh", action="store_true", default=False,
                  help="Log SSH output.")
    og.add_option("--latency-log", type=int, default=LATENCY_LOG,
                  help="Threshold for logging latency (ms) [%default]")
    op.add_option_group(og)

    (options, args) = op.parse_args(arglist)

    # check for required options without defaults
    if not options.domains and not options.tunnel_identifier:
        sys.stderr.write("Error: Missing required argument(s):"
                         " domains or identifier\n\n")
        op.print_help()
        raise SystemExit(1)
    for opt in ["user", "api_key", "host"]:
        if not hasattr(options, opt) or not getattr(options, opt):
            sys.stderr.write("Error: Missing required argument(s)\n\n")
            op.print_help()
            raise SystemExit(1)

    return options


def _get_loggable_options(options):
    ops = dict(options.__dict__)
    del ops['api_key']  # no need to log the API key
    return ops


def run(options,
        setup_signal_handler=setup_signal_handler,
        reverse_ssh=None,
        release=None,
        build=None):
    if not options.quiet:
        print ".---------------------------------------------------."
        print "|  Have questions or need help with Sauce Connect?  |"
        print "|  Contact us: http://support.saucelabs.com/forums  |"
        print "|  Terms of Service: http://saucelabs.com/tos       |"
        print "-----------------------------------------------------"
    logger.info("/ Starting \\")
    logger.info('Please wait for "You may start your tests" to start your tests.')

    metadata = dict(ScriptName='sauce_connect',
                    ScriptRelease=int(build),
                    Release=release,
                    Build=build,
                    Platform=platform.platform(),
                    PythonVersion=platform.python_version(),
                    OwnerHost=options.host,
                    OwnerPorts=options.ports,
                    Ports=options.tunnel_ports, )

    logger.debug("System is %s hours off UTC" %
                 (- (time.timezone, time.altzone)[time.daylight] / 3600.))
    logger.debug("options: %s" % _get_loggable_options(options))
    logger.debug("metadata: %s" % metadata)

    logger.info("Forwarding: %s:%s -> %s:%s", options.domains,
                options.tunnel_ports, options.host, options.ports)

    # Setup HealthChecker latency and make initial check of forwarded ports
    HealthChecker.latency_log = options.latency_log
    fail_msg = ("!! Are you sure this machine can get to your web server on "
                "host '%(host)s' listening on port %(port)d? Your tests will "
                "fail while the server is unreachable.")
    HealthChecker(options.host, options.ports, fail_msg=fail_msg).check()

    for attempt in xrange(1, RETRY_BOOT_MAX + 1):
        try:
            tunnel = TunnelMachine(options.rest_url, options.user,
                                   options.api_key, options.domains,
                                   options.ssh_port, bool(options.boost_mode),
                                   bool(options.ssh),
                                   options.fast_fail_regexps,
                                   options.direct_domains,
                                   options.no_ssl_bump_domains,
                                   options.shared_tunnel,
                                   options.tunnel_identifier,
                                   options.vm_version,
                                   options.squid_opts,
                                   metadata)
        except TunnelMachineError, e:
            logger.error(e)
            peace_out(returncode=1)
            return
        setup_signal_handler(tunnel, options)
        atexit.register(peace_out, tunnel, atexit=True)
        try:
            tunnel.ready_wait()
            break
        except TunnelMachineError, e:
            if dying:
                return
            logger.warning(e)
            if attempt < RETRY_BOOT_MAX:
                logger.info("Requesting new tunnel")
                continue
            logger.error("!! Could not get tunnel remote VM")
            logger.info("** Please contact help@saucelabs.com")
            peace_out(tunnel, returncode=1)
            return
        except ShutdownRequestedError, e:
            if dying:
                return
            logger.warning(e)
            peace_out(returncode=1)
            return

    def scala_cons(cls):
        class scala_subclass(cls):
            def __init__(self, **kwargs):
                cls.__init__(self)
                # Scala and Jython don't understand how to pass
                # constructor kwargs from Jython to Scala. What we can
                # do instead is, after the Scala constructor runs, use
                # all the keyword args to set the corresponding
                # members on the object.
                for k, v in kwargs.iteritems():
                    getattr(self, k + '_$eq')(v)
        return scala_subclass
    reverse_ssh = scala_cons(reverse_ssh)

    ssh = reverse_ssh(tunnel=tunnel, host=options.host,
                      ports=options.ports, tunnel_ports=options.tunnel_ports,
                      ssh_port=options.ssh_port,
                      use_ssh_config=options.use_ssh_config,
                      debug=options.debug_ssh,
                      se_port=options.se_port)
    try:
        ssh.run(options.readyfile)
    except (ReverseSSHError, TunnelMachineError), e:
        logger.error(e)
    except InterruptedException, e:
        logger.info("Exiting due to interrupt")
        return
    logger.info("Finished running tunnel")
    peace_out(tunnel)
