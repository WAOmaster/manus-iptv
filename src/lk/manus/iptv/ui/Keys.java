package lk.manus.iptv.ui;

import android.view.KeyEvent;

/** The remote-key map in one place: what the app does with each key (drives the dispatcher, the "Remote keys" help and the "Remote test" screen). */
public final class Keys {
    private Keys() {}

    // action ids the activity switches on
    public static final String FAV = "fav", SEARCH = "search", INFO = "info", GUIDE = "guide", CATS = "cats", SUBS = "subs",
            SETTINGS = "settings", PAUSE = "pause", PLAY = "play", TOGGLE = "toggle", FF = "ff", RW = "rw", NEXT = "next", PREV = "prev", OPTIONS = "options";

    public static final int VOICE_ASSIST = 231, TV_CONTENTS_MENU = 256, TV_MEDIA_CONTEXT_MENU = 257, SKIP_FWD = 272, SKIP_BACK = 273;

    /** Action for keys the dispatcher handles itself; null = handled elsewhere (D-pad, OK, Back, channel/number keys) or not used. */
    public static String action(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_PROG_RED: case KeyEvent.KEYCODE_BOOKMARK: return FAV;
            case KeyEvent.KEYCODE_PROG_GREEN: return CATS;
            case KeyEvent.KEYCODE_PROG_YELLOW: case KeyEvent.KEYCODE_CAPTIONS: return SUBS;
            case KeyEvent.KEYCODE_PROG_BLUE: case KeyEvent.KEYCODE_SEARCH: case VOICE_ASSIST: case KeyEvent.KEYCODE_ASSIST: return SEARCH;
            case KeyEvent.KEYCODE_INFO: return INFO;
            case KeyEvent.KEYCODE_GUIDE: case TV_CONTENTS_MENU: case TV_MEDIA_CONTEXT_MENU: return GUIDE;
            case KeyEvent.KEYCODE_SETTINGS: return SETTINGS;
            case KeyEvent.KEYCODE_MENU: return OPTIONS;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return TOGGLE;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: case KeyEvent.KEYCODE_MEDIA_STOP: return PAUSE;
            case KeyEvent.KEYCODE_MEDIA_PLAY: return PLAY;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return FF;
            case KeyEvent.KEYCODE_MEDIA_REWIND: return RW;
            case KeyEvent.KEYCODE_MEDIA_NEXT: case SKIP_FWD: return NEXT;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: case SKIP_BACK: return PREV;
            default: return null;
        }
    }

    /** Plain-English effect of a key for the Remote test screen; null = the app does nothing with it. */
    public static String describe(int k) {
        if ((k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) || (k >= KeyEvent.KEYCODE_NUMPAD_0 && k <= KeyEvent.KEYCODE_NUMPAD_9)) return "Type a channel number (0 = previous channel)";
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: return "Next channel (in the guide: move up)";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "Previous channel (in the guide: move down)";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "Open the guide (in the guide: back out)";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "Options (in the guide: open the list)";
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: return "OK: open the guide / choose (hold = favourite)";
            case KeyEvent.KEYCODE_BACK: return "Open the guide / go back one step";
            case KeyEvent.KEYCODE_CHANNEL_UP: return "Next channel (in the guide: one page down)";
            case KeyEvent.KEYCODE_CHANNEL_DOWN: return "Previous channel (in the guide: one page up)";
            case KeyEvent.KEYCODE_PAGE_UP: return "Next channel (in the guide: one page up)";
            case KeyEvent.KEYCODE_PAGE_DOWN: return "Previous channel (in the guide: one page down)";
            case KeyEvent.KEYCODE_LAST_CHANNEL: return "Previous channel you watched";
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE: return "System: volume (the app does not touch it)";
            default: break;
        }
        String a = action(k);
        if (a == null) return null;
        switch (a) {
            case FAV: return "Favourite on / off";
            case CATS: return "Open Categories";
            case SUBS: return "Subtitles chooser";
            case SEARCH: return "Open Search";
            case INFO: return "Channel info (now / next, quality, speed)";
            case GUIDE: return "Open / close the guide";
            case SETTINGS: return "Open Settings";
            case OPTIONS: return "Options (versions, subtitles, favourite)";
            case TOGGLE: return "Pause / resume";
            case PAUSE: return "Pause live TV (press Play to resume)";
            case PLAY: return "Resume";
            case FF: return "Forward 10 s (live: within the buffer), else next channel";
            case RW: return "Back 10 s (live: within the buffer), else previous channel";
            case NEXT: return "Next channel";
            case PREV: return "Previous channel";
            default: return null;
        }
    }

    /** Help page rows: {label, description, dot colour or 0}. */
    public static final Object[][] HELP = {
        {"Red", "Favourite on / off", 0xFFFF4D4D},
        {"Green", "Categories", 0xFF3DDC84},
        {"Yellow", "Subtitles", 0xFFFFC940},
        {"Blue", "Search", 0xFF4D9DFF},
        {"OK", "Open the guide (hold = favourite)", 0},
        {"Up / Down", "Next / previous channel", 0},
        {"Left", "Open the guide", 0},
        {"Right", "Options: versions, subtitles, favourite", 0},
        {"CH + / CH -, Page", "Change channel (in the guide: scroll a page)", 0},
        {"0 - 9", "Type a channel number; 0 = previous channel", 0},
        {"Last", "Previous channel you watched", 0},
        {"Guide, Contents", "Open / close the guide", 0},
        {"Info", "Channel info: now / next, quality, speed", 0},
        {"Menu", "Options", 0},
        {"Settings", "Open Settings", 0},
        {"Captions", "Subtitles", 0},
        {"Search, Mic", "Search", 0},
        {"Bookmark", "Favourite on / off", 0},
        {"Play / Pause / Stop", "Pause live TV; Play resumes where you left off", 0},
        {"FF, Rew", "Forward / back 10 s inside the live buffer", 0},
        {"Next, Prev", "Next / previous channel", 0},
        {"Back", "Open the guide, then back out step by step", 0},
        {"Volume, Mute, Home, Power", "Handled by the TV, not the app", 0},
    };
}
