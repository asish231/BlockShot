package com.blockshot.input;

/**
 * Pure, window-toolkit-independent model of first-person mouse capture.
 *
 * <p>This keeps the "is the mouse grabbed for look control?" decision in one
 * small, testable place, away from the GLFW glue. The real window callbacks feed
 * focus and click events in; the game loop reads {@link #captured()} to decide
 * whether pointer motion should steer the camera and which cursor mode to request.
 *
 * <p>The behaviour deliberately mirrors what players expect from first-person
 * games:
 * <ul>
 *   <li>while the window is focused the mouse is grabbed, so looking and moving
 *       work the instant the window is up;</li>
 *   <li>losing focus (Cmd-Tab / clicking another app) frees the real cursor so
 *       other apps stay usable;</li>
 *   <li>clicking back into an ungrabbed window only re-grabs the mouse and is
 *       swallowed, so that click does not also fire a weapon or place a block.</li>
 * </ul>
 */
public final class InputCaptureState {

    private boolean captured;

    public InputCaptureState(boolean initiallyCaptured) {
        this.captured = initiallyCaptured;
    }

    /** True when the mouse is grabbed and pointer motion should steer the camera. */
    public boolean captured() {
        return captured;
    }

    /**
     * Mirror the window focus state: a focused window grabs the mouse so the
     * player can look and move immediately, while losing focus releases it.
     */
    public void onFocusChanged(boolean focused) {
        captured = focused;
    }

    /**
     * Handle a mouse press. While ungrabbed (for example just after clicking back
     * into the window) the press must only re-grab the mouse and must not also be
     * treated as a game action, so this returns {@code true} to signal "swallow it".
     *
     * @return {@code true} if the press was consumed purely to re-grab the mouse
     */
    public boolean onPressShouldRecapture() {
        if (captured) {
            return false;
        }
        captured = true;
        return true;
    }
}
