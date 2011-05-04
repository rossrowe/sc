import traceback

from urllib2 import Request, URLError

from java.net import URL
from java.io import InputStreamReader, BufferedReader

class Response(object):
    msg = "OK"
    
    def __init__(self, doc):
        self.doc = doc
    def read(self):
        return self.doc
    
    def close(self):
        pass

class Opener(object):
    addheaders = []
    def open(self, url_or_req):
        try:
            if isinstance(url_or_req, Request):
                connection = URL(url_or_req.get_full_url()).openConnection()
                for header in self.addheaders:
                    connection.setRequestProperty(header[0], header[1])
                for key, value in url_or_req.header_items():
                    connection.setRequestProperty(key, value)
                
                connection.setRequestMethod(url_or_req.get_method())
                
                if url_or_req.get_data() != None:
                    connection.setDoOutput(True)
                    outstream = connection.getOutputStream()
                    outstream.write(url_or_req.get_data())
            else:
                connection = URL(url_or_req).openConnection()
                for header in self.addheaders:
                    connection.setRequestProperty(header[0], header[1])

            instream = connection.getInputStream()
            doc = ""
            reader = BufferedReader(InputStreamReader(instream))
            line = reader.readLine()
            while line:
                doc += line
                line = reader.readLine()
            return Response(doc)
        except:
            traceback.print_exc()
            raise

def urlopen(url):
    opener = Opener()
    return opener.open(url)

def build_opener():
    return Opener()