/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.mule.tck.junit4.AbstractMuleTestCase;
import org.mule.tck.size.SmallTest;

import java.io.Serializable;
import java.util.Map;

import org.apache.commons.lang.SerializationUtils;
import org.junit.Test;

@SmallTest
public class CaseInsensitiveHashMapTestCase extends AbstractMuleTestCase
{
    protected Map createTestMap()
    {
        Map map = new CaseInsensitiveHashMap();
        map.put("FOO", "BAR");
        map.put("DOO", Integer.valueOf(3));
        return map;
    }

    @Test
    public void testMap() throws Exception
    {
        Map map = createTestMap();
        doTestMap(map);
    }

    @Test
    public void testMapSerialization() throws Exception
    {
        Map map = createTestMap();
        doTestMap(map);

        byte[] bytes = SerializationUtils.serialize((Serializable) map);
        Map resultMap = (Map) SerializationUtils.deserialize(bytes);
        doTestMap(resultMap);
    }

    public void doTestMap(Map map) throws Exception
    {
        assertEquals("BAR", map.get("FOO"));
        assertEquals("BAR", map.get("foo"));
        assertEquals("BAR", map.get("Foo"));

        assertEquals(Integer.valueOf(3), map.get("DOO"));
        assertEquals(Integer.valueOf(3), map.get("doo"));
        assertEquals(Integer.valueOf(3), map.get("Doo"));

        assertEquals(2, map.size());

        // Test that the key set contains the same case as we put in
        for (Object o : map.keySet())
        {
            assertFalse(o.equals("foo") || o.equals("doo"));
        }
    }
}
