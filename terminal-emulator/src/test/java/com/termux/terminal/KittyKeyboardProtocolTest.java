package com.termux.terminal;

import android.view.KeyEvent;

/** Tests for the kitty keyboard protocol progressive enhancement flags and key encodings. */
public class KittyKeyboardProtocolTest extends TerminalTestCase {

    public void testTextKeysStayLegacyUnlessAllKeysAreReported() {
        // With only the disambiguate flag, Enter/Tab/Backspace must keep their legacy bytes so
        // the user can still run a shell command after a program crashes while the mode is set.
        assertNull(KeyHandler.getKittyCode(KeyEvent.KEYCODE_ENTER, 0, 1, 1));
        assertNull(KeyHandler.getKittyCode(KeyEvent.KEYCODE_TAB, 0, 1, 1));
        assertNull(KeyHandler.getKittyCode(KeyEvent.KEYCODE_DEL, 0, 1, 1));
        assertNull(KeyHandler.getKittyCode(KeyEvent.KEYCODE_ENTER, KeyHandler.KEYMOD_CTRL, 1, 1));

        // Report all keys as escape codes uses the C0 code points for the text keys.
        assertEquals("\033[13u", KeyHandler.getKittyCode(KeyEvent.KEYCODE_ENTER, 0, 1, 8));
        assertEquals("\033[9u", KeyHandler.getKittyCode(KeyEvent.KEYCODE_TAB, 0, 1, 8));
        assertEquals("\033[127u", KeyHandler.getKittyCode(KeyEvent.KEYCODE_DEL, 0, 1, 8));
    }

    public void testFunctionalKeysUseLegacyCompatibleForms() {
        // Escape is disambiguated as CSI u, arrows/Home/End keep their CSI final byte, and the
        // rest keep the CSI number ~ form. Sending the Private Use Area numbers (57350, ...)
        // here is wrong and makes every kitty aware application drop the key.
        assertEquals("\033[27u", KeyHandler.getKittyCode(KeyEvent.KEYCODE_ESCAPE, 0, 1, 1));

        assertEquals("\033[A", KeyHandler.getKittyCode(KeyEvent.KEYCODE_DPAD_UP, 0, 1, 1));
        assertEquals("\033[B", KeyHandler.getKittyCode(KeyEvent.KEYCODE_DPAD_DOWN, 0, 1, 1));
        assertEquals("\033[C", KeyHandler.getKittyCode(KeyEvent.KEYCODE_DPAD_RIGHT, 0, 1, 1));
        assertEquals("\033[D", KeyHandler.getKittyCode(KeyEvent.KEYCODE_DPAD_LEFT, 0, 1, 1));
        assertEquals("\033[H", KeyHandler.getKittyCode(KeyEvent.KEYCODE_MOVE_HOME, 0, 1, 1));
        assertEquals("\033[F", KeyHandler.getKittyCode(KeyEvent.KEYCODE_MOVE_END, 0, 1, 1));

        assertEquals("\033[1;2A", KeyHandler.getKittyCode(KeyEvent.KEYCODE_DPAD_UP, KeyHandler.KEYMOD_SHIFT, 1, 1));
        assertEquals("\033[1;5D", KeyHandler.getKittyCode(KeyEvent.KEYCODE_DPAD_LEFT, KeyHandler.KEYMOD_CTRL, 1, 1));
        assertEquals("\033[1;7C", KeyHandler.getKittyCode(KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyHandler.KEYMOD_CTRL | KeyHandler.KEYMOD_ALT, 1, 1));

        assertEquals("\033[5~", KeyHandler.getKittyCode(KeyEvent.KEYCODE_PAGE_UP, 0, 1, 1));
        assertEquals("\033[6~", KeyHandler.getKittyCode(KeyEvent.KEYCODE_PAGE_DOWN, 0, 1, 1));
        assertEquals("\033[5;5~", KeyHandler.getKittyCode(KeyEvent.KEYCODE_PAGE_UP, KeyHandler.KEYMOD_CTRL, 1, 1));
        assertEquals("\033[2~", KeyHandler.getKittyCode(KeyEvent.KEYCODE_INSERT, 0, 1, 1));
        assertEquals("\033[3~", KeyHandler.getKittyCode(KeyEvent.KEYCODE_FORWARD_DEL, 0, 1, 1));

        assertEquals("\033[11~", KeyHandler.getKittyCode(KeyEvent.KEYCODE_F1, 0, 1, 1));
        assertEquals("\033[13~", KeyHandler.getKittyCode(KeyEvent.KEYCODE_F3, 0, 1, 1));
        assertEquals("\033[24~", KeyHandler.getKittyCode(KeyEvent.KEYCODE_F12, 0, 1, 1));

        // Repeat and release events append ":event" to the modifier field.
        assertEquals("\033[1;1:2A", KeyHandler.getKittyCode(KeyEvent.KEYCODE_DPAD_UP, 0, 2, 3));
        assertEquals("\033[1;1:3A", KeyHandler.getKittyCode(KeyEvent.KEYCODE_DPAD_UP, 0, 3, 3));
        assertEquals("\033[6;1:3~", KeyHandler.getKittyCode(KeyEvent.KEYCODE_PAGE_DOWN, 0, 3, 3));
        assertEquals("\033[27;1:3u", KeyHandler.getKittyCode(KeyEvent.KEYCODE_ESCAPE, 0, 3, 3));
    }

    public void testQueryPushPopAndSet() {
        withTerminalSized(3, 3);

        enterString("\033[?u");
        assertEquals("\033[?0u", mOutput.getOutputAndClear());

        enterString("\033[>1u");
        assertEquals(1, mTerminal.getKittyKeyboardFlags());

        enterString("\033[?u");
        assertEquals("\033[?1u", mOutput.getOutputAndClear());

        enterString("\033[=13;2u");
        assertEquals(13, mTerminal.getKittyKeyboardFlags());

        enterString("\033[=4;3u");
        assertEquals(9, mTerminal.getKittyKeyboardFlags());

        enterString("\033[<u");
        assertEquals(0, mTerminal.getKittyKeyboardFlags());
    }

    public void testMainAndAlternateScreenKeepSeparateFlags() {
        withTerminalSized(3, 3);

        enterString("\033[>1u");
        assertEquals(1, mTerminal.getKittyKeyboardFlags());

        // A full screen application pushes its own flags on the alternate screen.
        enterString("\033[?1049h");
        assertEquals(0, mTerminal.getKittyKeyboardFlags());
        enterString("\033[>8u");
        assertEquals(8, mTerminal.getKittyKeyboardFlags());

        // If it is killed without popping (no CSI < u), the shell prompt on the main screen must
        // still see its original flags instead of the application's.
        enterString("\033[?1049l");
        assertEquals(1, mTerminal.getKittyKeyboardFlags());
    }

    public void testResetClearsFlags() {
        withTerminalSized(3, 3);
        enterString("\033[>8u");
        assertEquals(8, mTerminal.getKittyKeyboardFlags());
        enterString("\033c");
        assertEquals(0, mTerminal.getKittyKeyboardFlags());
    }
}
