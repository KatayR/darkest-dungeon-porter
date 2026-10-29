package org.ddport.darkest;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * One of the game's save files (persist.game.json and the others in a profile_<n>/ folder), read by
 * field path such as "base_root.estatename". Despite the name they are binary: a 64-byte header
 * (magic 01 B1 00 00), a table of objects (16 bytes each: parent, field, direct children, all
 * children), a table of fields (12 bytes each: name hash, offset in the data, then bit 0 "is an
 * object", bits 2-10 the name's length with its 0, bits 11-30 the object's index), and the data: each
 * field's name, then its value. Fields come in document order, an object's children right after it.
 * A value of 4 bytes or more starts 4-aligned; a string is its length (with the 0) and its bytes.
 */
final class SaveFile {
    private final ByteBuffer b;
    private final Map<String, int[]> values = new HashMap<>();   // path -> where its value starts and ends

    SaveFile(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        try {
            if (b.getInt(0) != 0xB101)
                throw new IOException("not a save file: " + file);
            int objects = b.getInt(24), count = b.getInt(44), table = b.getInt(48), data = b.getInt(60);
            int[] starts = new int[count + 1];   // each field's offset, sorted, then the data's end
            for (int i = 0; i < count; i++)
                starts[i] = b.getInt(table + 12 * i + 4);
            starts[count] = bytes.length - data;
            Arrays.sort(starts);
            Deque<Object[]> open = new ArrayDeque<>();   // the objects being filled: path, children left
            for (int i = 0; i < count; i++) {
                int offset = b.getInt(table + 12 * i + 4), info = b.getInt(table + 12 * i + 8);
                int nameLength = (info >> 2) & 0x1FF;
                String name = new String(bytes, data + offset, Math.max(nameLength - 1, 0),
                        java.nio.charset.StandardCharsets.ISO_8859_1);
                while (!open.isEmpty() && (int) open.peek()[1] == 0)
                    open.pop();
                String path = open.isEmpty() ? name : open.peek()[0] + "." + name;
                if (!open.isEmpty())
                    open.peek()[1] = (int) open.peek()[1] - 1;
                if ((info & 1) != 0) {
                    int object = (info >>> 11) & 0xFFFFF;
                    open.push(new Object[] { path, b.getInt(objects + 16 * object + 8) });
                } else {
                    int next = starts[Arrays.binarySearch(starts, offset) + 1];
                    values.put(path, new int[] { data + offset + nameLength, data + next });
                }
            }
        } catch (IndexOutOfBoundsException e) {
            throw new IOException("damaged save file: " + file, e);
        }
    }

    /** The string at `path`, or null if there is none. */
    String string(String path) {
        int[] v = values.get(path);
        if (v == null)
            return null;
        int at = align(v[0]);
        if (at + 4 > v[1])
            return null;
        int length = b.getInt(at);
        if (length < 1 || at + 4 + length > v[1])
            return null;
        return new String(b.array(), at + 4, length - 1, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The 32-bit int at `path`, or `otherwise`. */
    int integer(String path, int otherwise) {
        int[] v = values.get(path);
        return v == null || align(v[0]) + 4 > v[1] ? otherwise : b.getInt(align(v[0]));
    }

    /** The float at `path`, or `otherwise`. */
    float decimal(String path, float otherwise) {
        int[] v = values.get(path);
        return v == null || align(v[0]) + 4 > v[1] ? otherwise : b.getFloat(align(v[0]));
    }

    private static int align(int at) {
        return (at + 3) & ~3;
    }
}
