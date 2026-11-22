package com.gary.photosframe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class YouTubeLinkTest {
    private static void check(String text, String video, String list) {
        YouTubeLink l = YouTubeLink.parse(text);
        if (video == null && list == null) { assertNull(text, l); return; }
        assertEquals(text, video, l.video);
        assertEquals(text, list, l.list);
    }

    @Test public void videos() {
        check("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "dQw4w9WgXcQ", null);
        check("https://youtu.be/dQw4w9WgXcQ?si=abcDEF123", "dQw4w9WgXcQ", null);
        check("https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=42", "dQw4w9WgXcQ", null);
        check("https://music.youtube.com/watch?v=dQw4w9WgXcQ&feature=share", "dQw4w9WgXcQ", null);
        check("https://youtube.com/shorts/dQw4w9WgXcQ?feature=share", "dQw4w9WgXcQ", null);
        check("https://www.youtube.com/live/dQw4w9WgXcQ", "dQw4w9WgXcQ", null);
        check("youtu.be/dQw4w9WgXcQ", "dQw4w9WgXcQ", null);
        check("Never Gonna Give You Up\nhttps://youtu.be/dQw4w9WgXcQ", "dQw4w9WgXcQ", null);
    }

    @Test public void playlists() {
        check("https://youtube.com/playlist?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG&si=xyz",
                null, "PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG");
        check("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG&index=3",
                "dQw4w9WgXcQ", "PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG");
        check("https://music.youtube.com/playlist?list=OLAK5uy_kV2wW8mXQZx2lYq9dYk3Ga3dPl2f8J2zU",
                null, "OLAK5uy_kV2wW8mXQZx2lYq9dYk3Ga3dPl2f8J2zU");
    }

    @Test public void notYouTube() {
        check("", null, null);
        check("hello", null, null);
        check("https://vimeo.com/123456789", null, null);
        check("https://www.youtube.com/", null, null);
        check("https://youtu.be/short", null, null);
        check("https://evil.com/watch?v=dQw4w9WgXcQ", null, null);
    }
}
