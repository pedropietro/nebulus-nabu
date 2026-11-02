package io.github.pedropietro.nebulus.cid;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class CidTest {

    @Test
    public void rawCidOfHelloWorld() {
        Cid cid = Cid.of(Cid.RAW, "hello world".getBytes(StandardCharsets.UTF_8));
        assertEquals("bafkreifzjut3te2nhyekklss27nh3k72ysco7y32koao5eei66wof36n5e", cid.toString());
        assertEquals(1, cid.version());
        assertEquals(Cid.RAW, cid.codec());
        assertEquals(Cid.SHA2_256, cid.hashFunction());
        assertEquals(32, cid.digest().length);
    }

    @Test
    public void decodeRoundTripsV1() {
        String s = "bafybeicaivkonz2sofegu2thshbeod3723ye2i5c5ivsyhv7gyuyunyyoq";
        Cid cid = Cid.decode(s);
        assertEquals(s, cid.toString());
        assertEquals(Cid.DAG_PB, cid.codec());
        assertEquals(cid, Cid.fromBytes(cid.toBytes()));
        assertEquals(cid, Cid.decode("/ipfs/" + s));
        assertEquals(cid, Cid.decode(s.toUpperCase())); // multibase 'B' = base32 upper
    }

    @Test
    public void decodeRoundTripsV0() {
        String s = "QmVdFZgHnEgcedCS2G2ZNiEN59LuVrnRm7z3yXtEBv2XiF";
        Cid cid = Cid.decode(s);
        assertEquals(0, cid.version());
        assertEquals(Cid.DAG_PB, cid.codec());
        assertEquals(s, cid.toString());
        assertArrayEquals(Base58.decode(s), cid.toBytes());
    }

    @Test
    public void base58MultibaseV1() {
        Cid cid = Cid.decode("zb2rhYSxw4ZjuzgCnWSt19Q94ERaeFhu9uSqRgjSdx9bsgM6f");
        assertEquals(1, cid.version());
        assertEquals(Cid.RAW, cid.codec());
    }

    @Test
    public void matchesVerifiesContent() {
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        Cid cid = Cid.of(Cid.RAW, data);
        assertTrue(cid.matches(data));
        assertFalse(cid.matches("abd".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void codecIsPartOfIdentity() {
        byte[] data = new byte[]{1, 2, 3};
        assertNotEquals(Cid.of(Cid.RAW, data), Cid.of(Cid.DAG_PB, data));
    }

    @Test
    public void rejectsGarbage() {
        assertFalse(Cid.isValid(""));
        assertFalse(Cid.isValid("hello"));
        assertFalse(Cid.isValid("../etc/passwd"));
        assertFalse(Cid.isValid("bafy!!"));
        assertTrue(Cid.isValid("bafkreifzjut3te2nhyekklss27nh3k72ysco7y32koao5eei66wof36n5e"));
    }

    @Test
    public void base32RoundTrip() {
        for (int n = 0; n < 40; n++) {
            byte[] data = new byte[n];
            for (int i = 0; i < n; i++) {
                data[i] = (byte) (i * 37 + n);
            }
            assertArrayEquals(data, Base32.decode(Base32.encode(data)));
            assertArrayEquals(data, Base58.decode(Base58.encode(data)));
        }
    }
}
