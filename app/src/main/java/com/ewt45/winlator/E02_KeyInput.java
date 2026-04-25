package com.ewt45.winlator;

import android.util.Log;
import android.view.KeyEvent;

import com.winlator.xserver.XKeycode;
import com.winlator.xserver.XServer;

import java.util.Collections;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class E02_KeyInput {
    private static final String TAG = "E02_KeyInput";
    private static final int XK_UNICODE_PREFIX = 0x01000000;
    private static final long KEY_PRESS_DURATION_MS = 15;
    private static final long THREAD_IDLE_EXIT_MS = 3000;
    private static final int TASK_QUEUE_MAX_CAPACITY = 1024;

    // X11 keysym 常量
    private static final int XK_Return = 0xFF0D;
    private static final int XK_Tab = 0xFF09;
    private static final int XK_BackSpace = 0xFF08;
    private static final int XK_Escape = 0xFF1B;
    private static final int XK_Delete = 0xFFFF;

    private static final XKeycode[] STUB_KEYCODES = {
        XKeycode.KEY_CUSTOM_1, XKeycode.KEY_CUSTOM_2, XKeycode.KEY_CUSTOM_3,
        XKeycode.KEY_CUSTOM_4, XKeycode.KEY_CUSTOM_5, XKeycode.KEY_CUSTOM_6,
        XKeycode.KEY_CUSTOM_7, XKeycode.KEY_CUSTOM_8, XKeycode.KEY_CUSTOM_9,
        XKeycode.KEY_CUSTOM_10, XKeycode.KEY_CUSTOM_11, XKeycode.KEY_CUSTOM_12,
        XKeycode.KEY_CUSTOM_13, XKeycode.KEY_CUSTOM_14, XKeycode.KEY_CUSTOM_15,
        XKeycode.KEY_CUSTOM_16, XKeycode.KEY_CUSTOM_17
    };

    private static final LinkedBlockingQueue<XKeycode> availableKeycodes = new LinkedBlockingQueue<>();
    private static final LinkedBlockingQueue<CharacterTask> taskQueue = new LinkedBlockingQueue<>(TASK_QUEUE_MAX_CAPACITY);
    private static volatile Thread inputThread;
    private static volatile boolean isRunning;

    static {
        Collections.addAll(availableKeycodes, STUB_KEYCODES);
    }

    public static boolean handleAndroidKeyEvent(XServer xServer, KeyEvent event) {
        if (xServer == null || event == null) return false;

        if (event.getAction() == KeyEvent.ACTION_MULTIPLE) {
            String chars = event.getCharacters();
            if (chars != null && !chars.isEmpty()) {
                enqueueString(xServer, chars);
                startThreadIfNeeded();
                return true;
            }

            int repeat = event.getRepeatCount();
            int unicode = event.getUnicodeChar(event.getMetaState());
            if (unicode != 0 && repeat > 0) {
                enqueueRepeat(xServer, unicode, repeat);
                startThreadIfNeeded();
                return true;
            }
        }
        return false;
    }

    private static void enqueueString(XServer xServer, String text) {
        for (int i = 0, len = text.codePointCount(0, text.length()); i < len; i++) {
            enqueue(new CharacterTask(xServer, text.codePointAt(text.offsetByCodePoints(0, i))));
        }
    }

    private static void enqueueRepeat(XServer xServer, int codePoint, int count) {
        for (int i = 0; i < count; i++) {
            enqueue(new CharacterTask(xServer, codePoint));
        }
    }

    private static void enqueue(CharacterTask task) {
        if (!taskQueue.offer(task)) {
            CharacterTask dropped = taskQueue.poll();
            taskQueue.offer(task);
            if (dropped != null) {
                Log.w(TAG, "Queue full, dropped oldest task (max " + TASK_QUEUE_MAX_CAPACITY + ")");
            }
        }
    }

    private static void startThreadIfNeeded() {
        if (inputThread == null || !inputThread.isAlive()) {
            synchronized (E02_KeyInput.class) {
                if (inputThread == null || !inputThread.isAlive()) {
                    isRunning = true;
                    inputThread = new Thread(E02_KeyInput::processLoop, "Winlator-KeyInput");
                    inputThread.setDaemon(true);
                    inputThread.start();
                }
            }
        }
    }

    private static void processLoop() {
        Log.d(TAG, "Input thread started");
        try {
            while (isRunning) {
                CharacterTask task = taskQueue.poll(THREAD_IDLE_EXIT_MS, TimeUnit.MILLISECONDS);
                if (task == null) break;
                processChar(task.xServer, task.codePoint);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.d(TAG, "Input thread interrupted");
        } finally {
            isRunning = false;
            inputThread = null;
            Log.d(TAG, "Input thread exited");
        }
    }

    private static void processChar(XServer xServer, int codePoint) {
        XKeycode keycode = null;
        try {
            keycode = availableKeycodes.take();
            int keysym = mapToXKeySym(codePoint);

            xServer.injectKeyPress(keycode, keysym);
            Thread.sleep(KEY_PRESS_DURATION_MS);
            xServer.injectKeyRelease(keycode);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Interrupted: U+" + Integer.toHexString(codePoint));
        } catch (Exception e) {
            Log.e(TAG, "Failed: U+" + Integer.toHexString(codePoint), e);
        } finally {
            if (keycode != null) {
                availableKeycodes.add(keycode);
            }
        }
    }

    private static int mapToXKeySym(int codePoint) {
        switch (codePoint) {
            case '\n':
            case '\r':
                return XK_Return;
            case '\t':
                return XK_Tab;
            case '\b':
                return XK_BackSpace;
            case 0x1B:
                return XK_Escape;
            case 0x7F:
                return XK_Delete;
            default:
                return codePoint > 0xFF ? (codePoint | XK_UNICODE_PREFIX) : codePoint;
        }
    }

    private static class CharacterTask {
        final XServer xServer;
        final int codePoint;

        CharacterTask(XServer xServer, int codePoint) {
            this.xServer = xServer;
            this.codePoint = codePoint;
        }
    }

    public static void stop() {
        isRunning = false;
        if (inputThread != null) {
            inputThread.interrupt();
        }
        taskQueue.clear();
        Log.d(TAG, "Input thread stopped");
    }
}