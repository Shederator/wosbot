package dev.frostguard.tasks.combat;

/**
 * Thrown from inside a transition's input callback when the authorizing frame no longer permits
 * the input: the target is missing, the frame aged, or the action no longer fits. No input has
 * been sent, so the edge fails without consuming the frame or ending the session.
 */
final class BearInputRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    BearInputRefusedException(String reason) {
        super(reason);
    }
}
