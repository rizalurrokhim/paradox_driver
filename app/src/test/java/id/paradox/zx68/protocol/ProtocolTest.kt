package id.paradox.zx68.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test logika protokol yang **tidak** membutuhkan keyboard/emulator.
 *
 * Menguji bagian yang paling mudah salah saat porting:
 * - CRC-16/CCITT-FALSE harus sama dengan implementasi di `COR-M3-MKDriver.dll`
 * - susunan 64 byte frame
 * - encoding `DriverValue` untuk remap tombol
 */
class ProtocolTest {

    @Test
    fun `crc check value standar CCITT-FALSE`() {
        val crc = Crc16Ccitt.compute("123456789".toByteArray(Charsets.US_ASCII))
        assertEquals(0x29B1, crc)
    }

    @Test
    fun `frame handshake memiliki layout yang benar`() {
        val frame = M3Frame.build(
            command = ProtocolCommands.READ_MEMORY,
            sub = 9,
            address = 0,
            length = 9,
        )

        assertEquals(64, frame.size)
        assertEquals(0x01, frame[M3Frame.OFFSET_COMMAND].toInt() and 0xFF)
        assertEquals(9, frame[M3Frame.OFFSET_SUB].toInt() and 0xFF)
        assertEquals(0, frame[M3Frame.OFFSET_ADDRESS].toInt() and 0xFF)
        assertEquals(9, frame[M3Frame.OFFSET_LENGTH].toInt() and 0xFF)

        // CRC frame harus konsisten dengan perhitungan ulang.
        assertTrue(M3Frame.isValidResponse(frame))
    }

    @Test
    fun `alamat 24-bit ditulis little endian`() {
        val frame = M3Frame.build(
            command = ProtocolCommands.KEY_TABLE,
            sub = 1,
            address = 0x123456,
            length = 4,
            payload = byteArrayOf(0x00, 0x04, 0x00, 0x02),
        )
        assertEquals(0x56, frame[2].toInt() and 0xFF)
        assertEquals(0x34, frame[3].toInt() and 0xFF)
        assertEquals(0x12, frame[4].toInt() and 0xFF)

        val parsed = M3Frame.parse(frame)
        assertEquals(0x123456, parsed.address)
        assertEquals(1, parsed.sub)
        assertArrayEquals(byteArrayOf(0x00, 0x04, 0x00, 0x02), parsed.payload.copyOf(4))
    }

    @Test
    fun `payload dibatasi 56 byte`() {
        val frame = M3Frame.build(
            command = ProtocolCommands.WRITE_BLOCK,
            sub = 60,
            payload = ByteArray(60) { 0xAB.toByte() },
        )
        // Hanya 56 byte pertama yang masuk.
        assertEquals(0xAB.toByte(), frame[M3Frame.OFFSET_PAYLOAD])
        assertEquals(0xAB.toByte(), frame[M3Frame.PACKET_SIZE - 1])
        assertEquals(56, M3Frame.PAYLOAD_SIZE)
    }

    @Test
    fun `encoding driver value untuk tombol A`() {
        val value = KeyCodes.encode(usage = 0x04)
        assertEquals(0x02000400, value)

        val decoded = KeyCodes.decode(value)
        assertEquals("A", decoded.label)
        assertEquals(0x04, decoded.usage)
        assertEquals(KeyCodes.TYPE_KEYBOARD, decoded.type)
    }

    @Test
    fun `encoding modifier murni`() {
        val leftCtrl = KeyCodes.encode(usage = 0, modifiers = KeyCodes.MOD_LEFT_CTRL)
        assertEquals(0x02000001, leftCtrl)
        assertEquals("LeftCtrl", KeyCodes.decode(leftCtrl).label)
    }

    @Test
    fun `payload commit sesuai driver`() {
        assertEquals(4, ProtocolCommands.APPLY_PAYLOAD.size)
        assertEquals(0xF0.toByte(), ProtocolCommands.APPLY_PAYLOAD[0])
        assertEquals(0x01.toByte(), ProtocolCommands.APPLY_PAYLOAD[1])
        assertEquals(0x12.toByte(), ProtocolCommands.APPLY_PAYLOAD[2])
        assertEquals(0x03.toByte(), ProtocolCommands.APPLY_PAYLOAD[3])
    }

    @Test
    fun `command setelan LED tidak mengisi byte length`() {
        // Driver (RVA 0x2600/0x2750/0x28b0) hanya menulis sub + payload;
        // buf[5] tetap 0 setelah memset. Bila diisi, firmware bisa menolak.
        val led = M3Frame.build(
            command = ProtocolCommands.DEVICE_LED,
            sub = 1,
            length = 0,
            payload = byteArrayOf(241.toByte(), 3, 18, 0),
        )
        assertEquals(0, led[M3Frame.OFFSET_LENGTH].toInt() and 0xFF)
        // Payload tetap berada di offset 8 walau length 0.
        assertEquals(241.toByte(), led[M3Frame.OFFSET_PAYLOAD])
        assertEquals(18.toByte(), led[M3Frame.OFFSET_PAYLOAD + 2])
        assertTrue(M3Frame.isValidResponse(led))

        val apply = M3Frame.build(
            command = ProtocolCommands.APPLY,
            sub = 1,
            length = 0,
            payload = ProtocolCommands.APPLY_PAYLOAD,
        )
        assertEquals(0, apply[M3Frame.OFFSET_LENGTH].toInt() and 0xFF)
        assertEquals(0xF0.toByte(), apply[M3Frame.OFFSET_PAYLOAD])
    }

    @Test
    fun `identitas perangkat ZX68 dikenali`() {
        assertTrue(DeviceIds.isZx68(0x2726, 0x0094))
        assertTrue(DeviceIds.isZx68(0x2726, 0x00A6))
        // Model sekeluarga bukan ZX68 tetapi masih satu family chip.
        assertTrue(DeviceIds.isSameFamily(0x2726, 0x00A4))
        assertTrue(!DeviceIds.isZx68(0x2726, 0x00A4))
        assertTrue(!DeviceIds.isZx68(0x1234, 0x0094))
    }
}
