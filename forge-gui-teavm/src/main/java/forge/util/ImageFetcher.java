package forge.util;

/**
 * Web-target stub for {@code forge.util.ImageFetcher}.
 *
 * <p>The real {@code ImageFetcher} (in {@code forge-gui}) calls
 * {@code ThreadUtil.getServicePool()} which in turn holds static fields of
 * type {@code java.util.concurrent.ExecutorService} – a class absent from
 * TeaVM's JavaScript class library.  This causes a SEVERE compilation error
 * that produces a 0-byte {@code app.js}.
 *
 * <p>On the web target, images are served by the browser's HTTP mechanism and
 * loaded by gdx-teavm's asset pipeline.  The desktop-style download queue
 * implemented by the real {@code ImageFetcher} is therefore a no-op for the
 * web target.  Any card-art that is not bundled with the initial asset set
 * simply won't appear; a browser-native fetch strategy can be added later.
 */
public abstract class ImageFetcher {

    /** Translates a planechase card name to its image filename. */
    public static String getPlanechaseFilename(final String cardName) {
        return cardName.replace(" ", "_").replace("'", "")
                .replace("-", "").replace("!", "").replace(":", "") + ".jpg";
    }

    /** No-op: image downloading is handled by the browser on the web target. */
    public void fetchImage(final String imageKey, final Callback callback) {
    }

    /**
     * Subclass hook – returns a download {@link Runnable} for the given URLs.
     * On the web target this is never called (see {@link #fetchImage}).
     */
    protected abstract Runnable getDownloadTask(String[] downloadUrls,
            String destPath, Runnable notifyObservers);

    /** Callback invoked when an image has been downloaded. */
    public interface Callback {
        void onImageFetched();
    }
}
