package com.gme.sim.merchant.scheme;

/**
 * One populated field of a wire message, as shown in the terminal's annotated view.
 *
 * @param no      logical field number in the scheme spec
 * @param key     stable ASCII key
 * @param name    human-readable (e.g. Korean) field name
 * @param type    justification/padding rule (N/A/AN/ANY/NSP)
 * @param offset  byte offset of the field within the frame
 * @param length  fixed byte width of the field
 * @param value   the logical value placed in the field
 */
public record WireField(
        int no, String key, String name, String type,
        int offset, int length, String value) {}
