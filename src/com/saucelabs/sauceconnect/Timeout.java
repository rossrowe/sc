package com.saucelabs.sauceconnect;

public abstract class Timeout extends Thread {
    private long millis;
    
    private volatile boolean success = false;
    private volatile boolean error = false;
    
    private Object monitor = new Object();
    
    public Timeout(long millis){
        this.millis = millis;
    }
    
    public void go() {
        start();
        try {
            synchronized(monitor){
                long start = System.currentTimeMillis();
                monitor.wait(millis);
                if(System.currentTimeMillis()-start >= (millis)){
                    if(this.isAlive()){
                        this.interrupt();
                    }
                    success = false;
                }
            }
        } catch(InterruptedException e) {
            success = false;
        }
    }
    
    public void run() {
        try {
            longRunningTask();
            success = true;
        } catch (InterruptedException e){
            success = false;
        } catch (Exception e) {
            error = true;
        } finally {
            synchronized(monitor){
                monitor.notify();
            }
        }
    }
    
    public boolean isSuccess() {
        return success;
    }

    public boolean isError() {
        return error;
    }

    public abstract void longRunningTask() throws Exception;
}
