/*******************************************************************************
 * Copyright (c) 2012, 2015 Original authors and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Original authors and others - initial API and implementation
 ******************************************************************************/
package org.eclipse.nebula.widgets.nattable.data.convert;

import static org.junit.Assert.assertEquals;

import java.text.NumberFormat;
import java.util.Locale;

import org.junit.Test;

public class DefaultLongDisplayConverterTest {

    private DefaultLongDisplayConverter longConverter = new DefaultLongDisplayConverter();

    @Test
    public void testNonNullDataToDisplay() {
        assertEquals("123", this.longConverter.canonicalToDisplayValue(Long.valueOf("123")));
    }

    @Test
    public void testNullDataToDisplay() {
        assertEquals(null, this.longConverter.canonicalToDisplayValue(null));
    }

    @Test
    public void testNonNullDisplayToData() {
        assertEquals(Long.valueOf("123"), this.longConverter.displayToCanonicalValue("123"));
    }

    @Test
    public void testNullDisplayToData() {
        assertEquals(null, this.longConverter.displayToCanonicalValue(""));
    }

    @Test(expected = ConversionFailedException.class)
    public void testConversionException() {
        this.longConverter.displayToCanonicalValue("abc");
    }

    @Test
    public void testConvertLocalized() {
        this.longConverter.setNumberFormat(NumberFormat.getInstance(Locale.ENGLISH));
        assertEquals(Long.valueOf("1234"), this.longConverter.displayToCanonicalValue("1,234"));
        assertEquals("1,234", this.longConverter.canonicalToDisplayValue(Long.valueOf("1234")));
    }

    @Test(expected = ConversionFailedException.class)
    public void testFailConvertLocalized() {
        this.longConverter.setNumberFormat(null);
        assertEquals(Long.valueOf("1234"), this.longConverter.displayToCanonicalValue("1,234"));
    }

    @Test
    public void testConvertNonLocalized() {
        this.longConverter.setNumberFormat(null);
        assertEquals("1234", this.longConverter.canonicalToDisplayValue(Long.valueOf("1234")));
    }
}
