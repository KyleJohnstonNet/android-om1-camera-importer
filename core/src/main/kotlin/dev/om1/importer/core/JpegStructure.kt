package dev.om1.importer.core

import java.io.InputStream

/** Checks marker/segment boundaries through EOI; preserves any bytes after the image. */
object JpegStructure {
    fun verify(input: InputStream) {
        fun byte(): Int = input.read().also { require(it >= 0) { "Truncated JPEG." } }
        fun marker(): Int {
            require(byte()==0xff) { "Invalid JPEG marker." }
            var value=byte()
            while(value==0xff) value=byte()
            require(value!=0) { "Invalid JPEG marker." }
            return value
        }
        fun segment() {
            val length=(byte() shl 8) or byte()
            require(length>=2) { "Invalid JPEG segment." }
            var remaining=length-2
            val buffer=ByteArray(4096)
            while(remaining>0) {
                val count=input.read(buffer,0,minOf(buffer.size,remaining))
                require(count>0) { "Truncated JPEG segment." }
                remaining-=count
            }
        }
        require(byte()==0xff && byte()==0xd8) { "Not a JPEG." }
        var frame=false
        var scan=false
        var pending: Int?=null
        while(true) {
            val value=pending ?: marker()
            pending=null
            when(value) {
                0xd9 -> { require(frame && scan) { "JPEG has no image scan." }; return }
                0xd8 -> error("Unexpected JPEG start marker.")
                0x01 -> Unit
                in 0xd0..0xd7 -> error("Unexpected JPEG restart marker.")
                else -> {
                    segment()
                    if(value in setOf(0xc0,0xc1,0xc2,0xc3,0xc5,0xc6,0xc7,0xc9,0xca,0xcb,0xcd,0xce,0xcf)) frame=true
                    if(value==0xda) {
                        require(frame) { "JPEG scan precedes frame." }
                        scan=true
                        while(pending==null) {
                            if(byte()!=0xff) continue
                            var next=byte()
                            while(next==0xff) next=byte()
                            if(next==0 || next in 0xd0..0xd7) continue
                            pending=next
                        }
                    }
                }
            }
        }
    }
}
