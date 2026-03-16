package forge;

import forge.card.CardRules;
import java.util.Collections;

/**
 * Web-target stub for {@code forge.CardStorageReader}.
 *
 * <p>The real {@code CardStorageReader} uses
 * {@code java.util.concurrent.CountDownLatch} (absent from TeaVM's JS class
 * library) to parallelise card-file loading across thread-pool partitions.
 * This causes a SEVERE compilation error that produces a 0-byte app.js.
 *
 * <p>On the web target, card data is loaded through a different mechanism
 * (bundled assets served by the HTTP server).  This stub satisfies the
 * constructor and {@code loadCards()} call sites while returning an empty
 * card set, so the {@code FModel} static initialiser can complete without
 * crashing.  A real web-aware card loader can be added later.
 */
public class CardStorageReader {

    public interface ProgressObserver {
        void setOperationName(String name, boolean usePercents);
        void report(int current, int total);

        ProgressObserver emptyObserver = new ProgressObserver() {
            @Override public void setOperationName(String name, boolean usePercents) { }
            @Override public void report(int current, int total) { }
        };
    }

    public CardStorageReader(final String cardDataDir,
            final CardStorageReader.ProgressObserver progressObserver,
            boolean loadCardsLazily) {
    }

    /** Returns an empty iterable — card loading is deferred on the web target. */
    public final Iterable<CardRules> loadCards() {
        return Collections.emptyList();
    }
}
