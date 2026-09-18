package com.flowingsun.war_project.client;

import java.util.Optional;

public final class WargameCaptureNoticeHudState
{
    private static final int NOTICE_TTL_TICKS = 40;
    private static String text = "";
    private static int color = 0xFFFFFFFF;
    private static int ageTicks;

    private WargameCaptureNoticeHudState()
    {
    }

    public static void apply(String noticeText, int noticeColor)
    {
        text = noticeText == null ? "" : noticeText;
        color = noticeColor;
        ageTicks = 0;
    }

    public static void reset()
    {
        text = "";
        color = 0xFFFFFFFF;
        ageTicks = 0;
    }

    public static void tick()
    {
        if (text.isBlank())
        {
            return;
        }
        ageTicks++;
        if (ageTicks > NOTICE_TTL_TICKS)
        {
            reset();
        }
    }

    public static Optional<CaptureNotice> visibleNotice()
    {
        if (text.isBlank() || ageTicks > NOTICE_TTL_TICKS)
        {
            return Optional.empty();
        }
        return Optional.of(new CaptureNotice(text, color));
    }

    public record CaptureNotice(String text, int color)
    {
    }
}
