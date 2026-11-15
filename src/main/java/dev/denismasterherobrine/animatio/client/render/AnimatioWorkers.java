package dev.denismasterherobrine.animatio.client.render;

import dev.denismasterherobrine.animatio.client.AnimatioClientConfig;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * The worker threads used for pose evaluation and CPU skinning.
 * <p>
 * Work is shared through an atomic index: workers take items one by one, and the thread that {@linkplain Job#join()
 * joins} takes items too before waiting, so it never idles while work is left.
 */
public final class AnimatioWorkers {
    private static final Logger LOGGER = LoggerFactory.getLogger("Animatio");
    private static @Nullable ForkJoinPool pool;
    private static int poolSize = -1;
    private static volatile boolean warnedFailure;

    private AnimatioWorkers() {
    }

    /** Configured number of worker threads (0 = do everything on the calling thread). */
    public static int threads() {
        int configured = AnimatioClientConfig.workerThreads();
        if (configured >= 0) return configured;
        return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
    }

    private static @Nullable ForkJoinPool pool() {
        int size = threads();
        if (size != poolSize) {
            ForkJoinPool old = pool;
            pool = size > 0 ? new ForkJoinPool(size, AnimatioWorkers::newThread, null, false) : null;
            poolSize = size;
            if (old != null) old.shutdown();
        }
        return pool;
    }

    private static ForkJoinWorkerThread newThread(ForkJoinPool pool) {
        ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
        thread.setName("Animatio Worker-" + thread.getPoolIndex());
        thread.setDaemon(true);
        thread.setContextClassLoader(AnimatioWorkers.class.getClassLoader());
        return thread;
    }

    /** A batch of {@code n} items being processed in the background. */
    public static final class Job {
        private final int n;
        private final IntConsumer body;
        private final AtomicInteger next = new AtomicInteger();
        private final CountDownLatch done;

        private Job(int n, IntConsumer body, int tasks) {
            this.n = n;
            this.body = body;
            this.done = new CountDownLatch(tasks);
        }

        private void runWorker() {
            try {
                drain();
            } finally {
                done.countDown();
            }
        }

        private void drain() {
            int i;
            while ((i = next.getAndIncrement()) < n) {
                try {
                    body.accept(i);
                } catch (Throwable t) {
                    if (!warnedFailure) {
                        warnedFailure = true;
                        LOGGER.error("Animatio background work failed (further failures are not logged)", t);
                    }
                }
            }
        }

        /** Help with the remaining items, then wait until every item is done. */
        public void join() {
            drain();
            boolean interrupted = false;
            while (true) {
                try {
                    done.await();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * Start processing items {@code 0..n-1} in the background. Each item runs exactly once.
     *
     * @param grain roughly how many items one worker should get before more workers are worth waking
     */
    public static Job start(int n, int grain, IntConsumer body) {
        ForkJoinPool p = pool();
        int tasks = p == null ? 0 : Math.min(poolSize, Math.max(1, (n + grain - 1) / grain));
        Job job = new Job(n, body, tasks);
        for (int t = 0; t < tasks; t++) p.execute(job::runWorker);
        return job;
    }

    /** Process items {@code 0..n-1} with the workers and the calling thread, returning when all are done. */
    public static void parallelFor(int n, int grain, IntConsumer body) {
        if (n <= 0) return;
        if (n <= grain || threads() == 0) {
            for (int i = 0; i < n; i++) body.accept(i);
            return;
        }
        start(n, grain, body).join();
    }
}
