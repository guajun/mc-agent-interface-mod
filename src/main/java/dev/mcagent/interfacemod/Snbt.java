package dev.mcagent.interfacemod;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.EndTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * Compact SNBT writer.
 *
 * {@code NbtUtils.structureToSnbt} pretty-prints with newlines, which is fine for
 * reading but useless for a restore: a command cannot contain a newline. This
 * writes the same data on one line, so a snapshot line can be pasted straight
 * into {@code /summon <type> <x> <y> <z> <nbt>}.
 */
public final class Snbt {
    private Snbt() {
    }

    public static String write(Tag tag) {
        StringBuilder out = new StringBuilder();
        append(tag, out);
        return out.toString();
    }

    private static void append(Tag tag, StringBuilder out) {
        if (tag instanceof CompoundTag compound) {
            out.append('{');
            boolean first = true;
            for (String key : compound.keySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                quote(key, out).append(':');
                append(compound.get(key), out);
            }
            out.append('}');
        } else if (tag instanceof ListTag list) {
            out.append('[');
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    out.append(',');
                }
                append(list.get(index), out);
            }
            out.append(']');
        } else if (tag instanceof StringTag text) {
            quote(text.asString().orElse(""), out);
        } else if (tag instanceof ByteArrayTag array) {
            out.append("[B;");
            byte[] values = array.getAsByteArray();
            for (int index = 0; index < values.length; index++) {
                if (index > 0) {
                    out.append(',');
                }
                out.append(values[index]).append('b');
            }
            out.append(']');
        } else if (tag instanceof IntArrayTag array) {
            out.append("[I;");
            int[] values = array.getAsIntArray();
            for (int index = 0; index < values.length; index++) {
                if (index > 0) {
                    out.append(',');
                }
                out.append(values[index]);
            }
            out.append(']');
        } else if (tag instanceof LongArrayTag array) {
            out.append("[L;");
            long[] values = array.getAsLongArray();
            for (int index = 0; index < values.length; index++) {
                if (index > 0) {
                    out.append(',');
                }
                out.append(values[index]).append('L');
            }
            out.append(']');
        } else if (tag instanceof ByteTag value) {
            out.append(value.byteValue()).append('b');
        } else if (tag instanceof ShortTag value) {
            out.append(value.shortValue()).append('s');
        } else if (tag instanceof IntTag value) {
            out.append(value.intValue());
        } else if (tag instanceof LongTag value) {
            out.append(value.longValue()).append('L');
        } else if (tag instanceof FloatTag value) {
            out.append(value.floatValue()).append('f');
        } else if (tag instanceof DoubleTag value) {
            out.append(value.doubleValue()).append('d');
        } else if (tag instanceof EndTag) {
            out.append("{}");
        } else {
            throw new IllegalArgumentException("unsupported tag " + tag.getClass().getName());
        }
    }

    private static StringBuilder quote(String value, StringBuilder out) {
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(character);
            }
        }
        return out.append('"');
    }
}
