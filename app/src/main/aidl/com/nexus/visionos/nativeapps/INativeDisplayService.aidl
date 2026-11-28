package com.nexus.visionos.nativeapps;

import android.view.Surface;

// The whole privileged interface. It deliberately has no way to run a command.
interface INativeDisplayService {
    // Shizuku's reserved transaction for stopping a user service.
    void destroy() = 16777114;

    // Returns the new display's id.
    int create(in Surface surface, int width, int height, int dpi) = 1;
    void resize(int displayId, int width, int height, int dpi, in Surface surface) = 2;
    // Opens the normal launcher screen of an installed app. Returns "" or a short reason.
    String launch(int displayId, String packageName) = 3;
    boolean touch(int displayId, int action, float x, float y, long downTime) = 4;
    boolean key(int displayId, int action, int keyCode, int metaState) = 5;
    void release(int displayId) = 6;
}
