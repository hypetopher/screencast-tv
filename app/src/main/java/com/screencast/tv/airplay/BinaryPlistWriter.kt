package com.screencast.tv.airplay

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Simple binary plist ("bplist00") encoder supporting the types needed for AirPlay:
 * dict, string, integer, data (raw bytes), boolean, array.
 */
object BinaryPlistWriter {

    fun write(root: Map<String, Any>): ByteArray {
        // Flatten all objects into a list (assign indices)
        val objects = mutableListOf<Any>()
        val objectIndex = mutableMapOf<Any, Int>()

        fun addObject(obj: Any): Int {
            // For maps and lists, always add new (don't deduplicate)
            if (obj is Map<*, *> || obj is List<*>) {
                val idx = objects.size
                objects.add(obj)
                // Don't put in objectIndex for collections
                // Recursively add children
                if (obj is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val map = obj as Map<String, Any>
                    for ((key, value) in map) {
                        addObject(key)
                        addObject(value)
                    }
                } else if (obj is List<*>) {
                    for (item in obj) {
                        if (item != null) addObject(item)
                    }
                }
                return idx
            }
            // For primitives, deduplicate
            objectIndex[obj]?.let { return it }
            val idx = objects.size
            objects.add(obj)
            objectIndex[obj] = idx
            return idx
        }

        addObject(root)

        // Determine ref size (1 byte if < 256 objects, 2 if < 65536)
        val numObjects = objects.size
        val refSize = if (numObjects < 256) 1 else 2

        // Serialize each object and record offsets
        val offsets = mutableListOf<Int>()
        val body = ByteArrayOutputStream()

        for (obj in objects) {
            offsets.add(body.size())
            writeObject(body, obj, objects, objectIndex, refSize)
        }

        // Build the full plist
        val out = ByteArrayOutputStream()

        // Header
        out.write("bplist00".toByteArray(Charsets.US_ASCII))

        // Object data
        val bodyBytes = body.toByteArray()
        out.write(bodyBytes)

        // Offset table
        val offsetTableOffset = 8 + bodyBytes.size
        val offsetSize = when {
            offsetTableOffset + numObjects * 4 < 256 -> 1
            offsetTableOffset + numObjects * 4 < 65536 -> 2
            else -> 4
        }
        for (offset in offsets) {
            val actualOffset = offset + 8 // Add header size
            writeIntBytes(out, actualOffset, offsetSize)
        }

        // Trailer (32 bytes)
        // 6 unused bytes
        out.write(ByteArray(6))
        // offset size (1 byte)
        out.write(offsetSize)
        // ref size (1 byte)
        out.write(refSize)
        // num objects (8 bytes big-endian)
        writeInt64(out, numObjects.toLong())
        // top object index (8 bytes big-endian)
        writeInt64(out, 0L)
        // offset table offset (8 bytes big-endian)
        writeInt64(out, (offsetTableOffset).toLong())

        return out.toByteArray()
    }

    private fun writeObject(
        out: ByteArrayOutputStream,
        obj: Any,
        allObjects: List<Any>,
        objectIndex: Map<Any, Int>,
        refSize: Int
    ) {
        when (obj) {
            is Boolean -> {
                out.write(if (obj) 0x09 else 0x08)
            }
            is Int -> writeInt(out, obj.toLong())
            is Long -> writeInt(out, obj)
            is String -> writeString(out, obj)
            is ByteArray -> writeData(out, obj)
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = obj as Map<String, Any>
                val count = map.size
                writeTypeAndCount(out, 0xD0, count)
                // Key refs
                for (key in map.keys) {
                    val idx = objectIndex[key] ?: 0
                    writeIntBytes(out, idx, refSize)
                }
                // Value refs
                for (value in map.values) {
                    val idx = if (value is Map<*, *> || value is List<*>) {
                        allObjects.indexOf(value)
                    } else {
                        objectIndex[value] ?: 0
                    }
                    writeIntBytes(out, idx, refSize)
                }
            }
            is List<*> -> {
                val count = obj.size
                writeTypeAndCount(out, 0xA0, count)
                for (item in obj) {
                    if (item != null) {
                        val idx = if (item is Map<*, *> || item is List<*>) {
                            allObjects.indexOf(item)
                        } else {
                            objectIndex[item] ?: 0
                        }
                        writeIntBytes(out, idx, refSize)
                    }
                }
            }
        }
    }

    private fun writeTypeAndCount(out: ByteArrayOutputStream, typeNibble: Int, count: Int) {
        if (count < 15) {
            out.write(typeNibble or count)
        } else {
            out.write(typeNibble or 0x0F)
            // Write count as int object inline
            writeInt(out, count.toLong())
        }
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Long) {
        when {
            value in 0..0xFF -> {
                out.write(0x10) // 1-byte int
                out.write(value.toInt())
            }
            value in 0..0xFFFF -> {
                out.write(0x11) // 2-byte int
                out.write((value shr 8).toInt() and 0xFF)
                out.write(value.toInt() and 0xFF)
            }
            value in 0..0xFFFFFFFFL -> {
                out.write(0x12) // 4-byte int
                out.write((value shr 24).toInt() and 0xFF)
                out.write((value shr 16).toInt() and 0xFF)
                out.write((value shr 8).toInt() and 0xFF)
                out.write(value.toInt() and 0xFF)
            }
            else -> {
                out.write(0x13) // 8-byte int
                for (i in 7 downTo 0) {
                    out.write((value shr (i * 8)).toInt() and 0xFF)
                }
            }
        }
    }

    private fun writeString(out: ByteArrayOutputStream, str: String) {
        val bytes = str.toByteArray(Charsets.US_ASCII)
        writeTypeAndCount(out, 0x50, bytes.size)
        out.write(bytes)
    }

    private fun writeData(out: ByteArrayOutputStream, data: ByteArray) {
        writeTypeAndCount(out, 0x40, data.size)
        out.write(data)
    }

    private fun writeIntBytes(out: ByteArrayOutputStream, value: Int, size: Int) {
        when (size) {
            1 -> out.write(value and 0xFF)
            2 -> {
                out.write((value shr 8) and 0xFF)
                out.write(value and 0xFF)
            }
            4 -> {
                out.write((value shr 24) and 0xFF)
                out.write((value shr 16) and 0xFF)
                out.write((value shr 8) and 0xFF)
                out.write(value and 0xFF)
            }
        }
    }

    private fun writeInt64(out: ByteArrayOutputStream, value: Long) {
        for (i in 7 downTo 0) {
            out.write((value shr (i * 8)).toInt() and 0xFF)
        }
    }
}
