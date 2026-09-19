package com.flowingsun.war_project.client.cef;

/**
 * Maps GLFW key codes (what Minecraft reports) to the Windows virtual key codes CEF expects for the
 * keys the transfer panel actually uses. Letters and digits already share the same values in both
 * worlds, so only the special keys need a table.
 */
final class CefKeyMap {
    private CefKeyMap() {
    }

    static int toCefKey(int glfwKey) {
        if (glfwKey >= 32 && glfwKey <= 96) {
            // Letters, digits and the digit-row punctuation line up with VK codes.
            return glfwKey;
        }
        return switch (glfwKey) {
            case 257 -> 13;   // enter
            case 258 -> 9;    // tab
            case 259 -> 8;    // backspace
            case 260 -> 45;   // insert
            case 261 -> 46;   // delete
            case 262 -> 39;   // right
            case 263 -> 37;   // left
            case 264 -> 40;   // down
            case 265 -> 38;   // up
            case 266 -> 33;   // page up
            case 267 -> 34;   // page down
            case 268 -> 36;   // home
            case 269 -> 35;   // end
            case 280 -> 144;  // caps lock
            case 320, 321, 322, 323, 324, 325, 326, 327, 328, 329 -> 96 + (glfwKey - 320); // keypad 0-9
            case 330 -> 110;  // keypad decimal
            case 334 -> 107;  // keypad add
            case 333 -> 109;  // keypad subtract
            case 332 -> 106;  // keypad multiply
            case 331 -> 111;  // keypad divide
            default -> 0;
        };
    }
}
