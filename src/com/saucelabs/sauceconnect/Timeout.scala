package com.saucelabs.sauceconnect

abstract class Timeout(millis:Long) extends Thread {
    @volatile
    private var success = false
    @volatile
    private var error = false

    private var monitor = new Object()

    def go() = {
        start()
        try {
            monitor.synchronized{
                val start = System.currentTimeMillis()
                monitor.wait(millis)
                if(System.currentTimeMillis()-start >= (millis)){
                    if(this.isAlive()){
                        this.interrupt()
                    }
                    success = false
                }
            }
        } catch {
            case e:InterruptedException => success = false
        }
    }

    override def run() = {
        try {
            longRunningTask()
            success = true
        } catch {
            case e:InterruptedException => success = false
            case e => error = true
        } finally {
            monitor.synchronized{
                monitor.notify()
            }
        }
    }

    def isSuccess() = success
    def isError() = error

    @throws(classOf[Exception])
    def longRunningTask()
}
