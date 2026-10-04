package br.com.walletpix.service.application;

/**
 * How a failed message should be treated by the consumer. Anything else thrown is treated as
 * retryable too: when in doubt, redeliver - every handler is idempotent.
 */
public final class MessageFailures {

    private MessageFailures() {
    }

    /** The message itself is wrong (unknown ISPB, malformed id...): redelivering cannot help, send it to the DLQ. */
    public static class PermanentFailure extends RuntimeException {
        public PermanentFailure(String message) {
            super(message);
        }
    }

    /**
     * Could succeed later: a dependency is down, or the message arrived before the one it refers
     * to (e.g. a status report for a payment not committed yet). Left on the queue to be retried.
     */
    public static final class RetryLater extends RuntimeException {
        public RetryLater(String message) {
            super(message);
        }

        public RetryLater(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
