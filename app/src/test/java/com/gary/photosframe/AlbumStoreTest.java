package com.gary.photosframe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

public class AlbumStoreTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void savesAndLoadsAlbums() throws Exception {
        File f = new File(tmp.getRoot(), "albums.json");
        AlbumStore s = new AlbumStore(f);
        AlbumStore.Album a = s.create("Mom & Dad \"2026\"\tñ", 1_763_800_000_000L);
        s.add(a.id, Arrays.asList("x1.jpg", "x2.jpg"));
        a.lastPlayed = 1_763_900_000_123L;
        a.gone = true;
        a.goneCount = 2;
        a.start = "2026:10:01";
        a.end = "2026:10:09";
        assertTrue(s.save());
        assertFalse(new File(f.getPath() + ".tmp").exists());

        AlbumStore back = new AlbumStore(f);
        AlbumStore.Album b = back.get(a.id);
        assertNotNull(b);
        assertEquals(a.name, b.name);
        assertEquals(1_763_800_000_000L, b.created);
        assertEquals(1_763_900_000_123L, b.lastPlayed);
        assertTrue(b.gone);
        assertEquals(2, b.goneCount);
        assertEquals("2026:10:01", b.start);
        assertEquals("2026:10:09", b.end);
        assertEquals(Arrays.asList("x1.jpg", "x2.jpg"), new java.util.ArrayList<>(b.files));
        assertSame(b, back.albumOf("x2.jpg"));
        assertSame(b, back.named("mom & dad \"2026\"\tñ"));
    }

    @Test public void aPhotoBelongsToOneAlbum() {
        AlbumStore s = new AlbumStore(new File(tmp.getRoot(), "albums.json"));
        AlbumStore.Album a = s.create("A", 1), b = s.create("B", 1);
        assertTrue(!a.id.equals(b.id));                     // same clock tick still gives two ids
        assertEquals(2, s.add(a.id, Arrays.asList("p.jpg", "q.jpg")));
        assertEquals(0, s.add(a.id, Collections.singletonList("p.jpg")));   // already there
        assertEquals(1, s.add(b.id, Collections.singletonList("p.jpg")));   // moves
        assertSame(b, s.albumOf("p.jpg"));
        assertFalse(a.files.contains("p.jpg"));
        assertEquals(0, s.add("nope", Collections.singletonList("r.jpg")));
        assertNull(s.albumOf("r.jpg"));
        assertFalse(s.rename(a.id, "  "));                  // an album keeps a name
        assertTrue(s.rename(a.id, " Egypt "));
        assertEquals("Egypt", a.name);
    }

    @Test public void idsFitInTheSettingString() {
        AlbumStore s = new AlbumStore(new File(tmp.getRoot(), "albums.json"));
        String id = s.create("x", System.currentTimeMillis()).id;
        assertTrue(id.matches("[a-z0-9-]+"));
    }

    @Test public void damagedFileStartsEmpty() throws Exception {
        File f = new File(tmp.getRoot(), "albums.json");
        Files.write(f.toPath(), "{\"albums\":[{\"id\":".getBytes(StandardCharsets.UTF_8));
        AlbumStore s = new AlbumStore(f);
        assertTrue(s.all().isEmpty());
    }

    @Test public void tokens() {
        String chosen = "2026:10:03~2026:10:09;album:a1b2;album:zz-2";
        assertEquals(3, AlbumStore.tokens(chosen).length);
        assertEquals(0, AlbumStore.tokens("").length);
        assertEquals("a1b2", AlbumStore.albumId(AlbumStore.token("a1b2")));
        assertNull(AlbumStore.albumId("2026:10:03~2026:10:09"));
        assertTrue(AlbumStore.chosenAlbum(chosen, "a1b2"));
        assertTrue(AlbumStore.chosenAlbum(chosen, "zz-2"));
        assertFalse(AlbumStore.chosenAlbum(chosen, "a1"));
        assertTrue(AlbumStore.chosenRange(chosen, "2026:10:05", "2026:10:12"));
        assertFalse(AlbumStore.chosenRange(chosen, "2025:01:01", "2025:01:04"));
        assertFalse(AlbumStore.chosenRange("album:a1b2", "2026:10:05", "2026:10:12"));   // albums never match dates
        // Values saved before albums keep working.
        assertTrue(AlbumStore.chosenRange("2026:10:03~2026:10:09;2025:11:01~2025:11:12", "2025:11:02", "2025:11:02"));
        assertFalse(AlbumStore.chosenAlbum("2026:10:03~2026:10:09", "a1b2"));
    }

    @Test public void tinyJsonReadsWhatItWrites() {
        String s = "line\n\"quoted\" \\ back\u0001";
        assertEquals(s, TinyJson.parse(TinyJson.quote(s)));
        Object o = TinyJson.parse(" {\"a\": [1, 2.5, true, null, \"\\u00e9\"], \"b\": {}} ");
        assertEquals(Arrays.asList(1.0, 2.5, Boolean.TRUE, null, "é"), ((java.util.Map<?, ?>) o).get("a"));
    }
}
