package forge.util;

import forge.gui.FThreads;

import java.util.function.Consumer;

public abstract class WaitCallback<T> implements Consumer<T>, Runnable {
    public class Lock {
    }

    private final Lock lock = new Lock();

    private T result;
    private volatile Throwable edtException;

    @Override
    public final void accept(T result0) {
        result = result0;
        synchronized (lock) {
            lock.notify();
        }
    }

    public final T invokeAndWait() {
        FThreads.assertExecutedByEdt(false); //not supported if on UI thread
        FThreads.invokeInEdtLater(() -> {
            try {
                WaitCallback.this.run();
            } catch (Throwable t) {
                edtException = t;
                accept(null);
            }
        });
        try {
            synchronized (lock) {
                lock.wait();
            }
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (edtException != null) {
            throw new RuntimeException("Exception in EDT during WaitCallback", edtException);
        }
        return result;
    }
}
