package java.util.concurrent;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Stub for RoboVM: {@code java.util.concurrent.CompletableFuture} is absent
 * from MobiVM's robovm-rt (which is based on the Android 4.4 / Java 7 class
 * library).  This stub provides the subset of the Java 8 API used by Forge
 * on iOS.
 *
 * <p>Tasks are executed on a shared cached thread pool that creates daemon
 * threads, matching the real CompletableFuture's behaviour of not preventing
 * JVM shutdown.  The {@code exceptionally} stage is implemented correctly: if
 * the upstream task throws, the exception handler is invoked and its return
 * value becomes the result of the returned future.
 */
public class CompletableFuture<T> {

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "CompletableFuture-worker");
                    t.setDaemon(true);
                    return t;
                }
            });

    /** Timeout in seconds used when waiting for individual futures in {@link #allOf}. */
    private static final long TASK_TIMEOUT_SECONDS = 60;

    private final Future<T> future;

    private CompletableFuture(Future<T> future) {
        this.future = future;
    }

    /** Asynchronously executes {@code supplier} on the shared thread pool. */
    public static <U> CompletableFuture<U> supplyAsync(final Supplier<U> supplier) {
        Future<U> f = EXECUTOR.submit(new Callable<U>() {
            @Override
            public U call() {
                return supplier.get();
            }
        });
        return new CompletableFuture<U>(f);
    }

    /**
     * Returns a new CompletableFuture that, if this stage completed
     * exceptionally, applies {@code fn} to the thrown exception and uses the
     * result as the value of the returned future.  If this stage completed
     * normally, the returned future holds the same result.
     */
    public CompletableFuture<T> exceptionally(final Function<Throwable, ? extends T> fn) {
        final Future<T> upstream = future;
        Future<T> wrapped = EXECUTOR.submit(new Callable<T>() {
            @Override
            public T call() {
                try {
                    return upstream.get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    return fn.apply(cause);
                } catch (Exception e) {
                    return fn.apply(e);
                }
            }
        });
        return new CompletableFuture<T>(wrapped);
    }

    /**
     * Returns a new CompletableFuture that completes when all of the given
     * futures complete (normally or exceptionally).
     */
    public static CompletableFuture<Void> allOf(final CompletableFuture<?>... cfs) {
        Future<Void> f = EXECUTOR.submit(new Callable<Void>() {
            @Override
            public Void call() {
                for (CompletableFuture<?> cf : cfs) {
                    try {
                        cf.future.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        // Each stage handles its own exceptions; ignore here.
                    }
                }
                return null;
            }
        });
        return new CompletableFuture<Void>(f);
    }

    /**
     * Waits for this future to complete and returns its result, throwing an
     * unchecked exception on failure — matching the behaviour of the real
     * {@code CompletableFuture.join()}.
     */
    public T join() {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause != null ? cause : e);
        }
    }
}
