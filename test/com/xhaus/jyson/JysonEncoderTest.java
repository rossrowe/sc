package com.xhaus.jyson;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.python.core.PyBoolean;
import org.python.core.PyDictionary;

public class JysonEncoderTest {
    @Test
    public void testEncodingBooleans() {
        JysonEncoder encoder = new JysonEncoder();
        PyDictionary container = new PyDictionary();
        container.put("foo", new PyBoolean(true));
        String encoded;
        try {
            encoded = encoder.json_repr(container);
            assertEquals("{\"foo\":true}", encoded);
        } catch(Exception e) {
            System.out.println(e);
        }
    }
}
