package forge.util;

import forge.gui.FThreads;

public abstract class WaitRunnable implements Runnable {
    public class Lock {
    }

    private final Lock lock = new Lock();
    private volatile Throwable edtException;

    public final void invokeAndWait() {
        FThreads.assertExecutedByEdt(false); //not supported if on UI thread
        FThreads.invokeInEdtLater(() -> {
            try {
                WaitRunnable.this.run();
            } catch (Throwable t) {
                edtException = t;
            } finally {
                synchronized(lock) {
                    lock.notify();
                }
            }
        });
        try {
            synchronized(lock) {
                lock.wait();
            }
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (edtException != null) {
            throw new RuntimeException("Exception in EDT during WaitRunnable", edtException);
        }
    }
}
