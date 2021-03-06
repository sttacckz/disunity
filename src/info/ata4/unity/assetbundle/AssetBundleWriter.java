/*
 ** 2014 December 03
 **
 ** The author disclaims copyright to this source code.  In place of
 ** a legal notice, here is a blessing:
 **    May you do good and not evil.
 **    May you find forgiveness for yourself and forgive others.
 **    May you share freely, never taking more than you give.
 */
package info.ata4.unity.assetbundle;

import info.ata4.io.DataWriter;
import info.ata4.io.socket.Sockets;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static java.nio.file.StandardOpenOption.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import info.ata4.io.lzma.LzmaEncoderProps;
import net.contrapunctus.lzma.LzmaOutputStream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.MutablePair;

/**
 *
 * @author Nico Bergemann <barracuda415 at yahoo.de>
 */
public class AssetBundleWriter {
    
    private final AssetBundleHeader header = new AssetBundleHeader();
    private final List<AssetBundleEntry> entries = new ArrayList<>();
    private final Map<AssetBundleEntry, MutablePair<Long, Long>> levelOffsetMap = new LinkedHashMap<>();
    
    public AssetBundleHeader getHeader() {
        return header;
    }
    
    public void addEntry(AssetBundleEntry entry) {
        entries.add(entry);
    }
    
    public void clearEntries() {
        entries.clear();
    }
    
    public void write(Path file) throws IOException {
        // add offset placeholders
        levelOffsetMap.clear();
        for (AssetBundleEntry entry : entries) {
            String name = entry.getName();
            if (name.equals("mainData") || name.startsWith("level") || entries.size() == 1) {
                levelOffsetMap.put(entry, new MutablePair<>(0L, 0L));
            }
        }

        header.getLevelByteEnd().clear();
        header.getLevelByteEnd().addAll(levelOffsetMap.values());
        header.setNumberOfLevelsToDownload(levelOffsetMap.size());
        
        try (DataWriter out = new DataWriter(Sockets.forFile(file,
                CREATE, WRITE, TRUNCATE_EXISTING))) {
            // write header
            header.write(out);
            header.setHeaderSize((int) out.position());

            // write bundle data
            if (header.isCompressed()) {
                // write data to temporary file
                Path dataFile = Files.createTempFile(file.getParent(), "uncompressedData", null);
                try (DataWriter outData = new DataWriter(Sockets.forFile(dataFile,
                            CREATE, READ, WRITE, TRUNCATE_EXISTING, DELETE_ON_CLOSE))) {
                    writeData(outData);
                    
                    // configure LZMA encoder
                    LzmaEncoderProps props = new LzmaEncoderProps();
                    props.setDictionarySize(1 << 23); // 8 MiB
                    props.setNumFastBytes(273); // maximum
                    props.setUncompressedSize(outData.size());
                    props.setEndMarkerMode(true);
                    
                    // stream the temporary bundle data compressed into the bundle file
                    outData.position(0);
                    
                    try (
                        InputStream is = outData.getSocket().getInputStream();
                        OutputStream os = new LzmaOutputStream(new BufferedOutputStream(
                                out.getSocket().getOutputStream()), props);
                    ) {
                        IOUtils.copy(is, os);
                    }
                }
                
                for (MutablePair<Long, Long> levelOffset : levelOffsetMap.values()) {
                    levelOffset.setLeft(out.size());
                }
            } else {
                // write data directly to file
                writeData(out);
            }
            
            // update header
            int fileSize = (int) out.size();
            header.setCompleteFileSize(fileSize);
            header.setMinimumStreamedBytes(fileSize);
            
            out.position(0);
            out.writeStruct(header);
        }
    }
    
    private void writeData(DataWriter out) throws IOException {
        // write entry list
        long baseOffset = out.position();
        out.writeInt(entries.size());

        List<AssetBundleEntryInfo> entryInfos = new ArrayList<>(entries.size());
        for (AssetBundleEntry entry : entries) {
            AssetBundleEntryInfo entryInfo = new AssetBundleEntryInfo();
            entryInfo.setName(entry.getName());
            entryInfo.setSize(entry.getSize());
            entryInfo.write(out);
            entryInfos.add(entryInfo);
        }
        
        // write entry data
        for (int i = 0; i < entries.size(); i++) {
            out.align(4);
            
            AssetBundleEntry entry = entries.get(i);
            AssetBundleEntryInfo entryInfo = entryInfos.get(i);
            
            entryInfo.setOffset(out.position() - baseOffset);
            
            if (i == 0) {
                header.setDataHeaderSize(entryInfo.getOffset());
            }
            
            try (
                InputStream is = entry.getInputStream();
                OutputStream os = out.getSocket().getOutputStream();
            ) {
                IOUtils.copy(is, os);
            }
            
            MutablePair<Long, Long> levelOffset = levelOffsetMap.get(entry);
            if (levelOffset != null) {
                long offset = out.position() - baseOffset;
                levelOffset.setLeft(offset);
                levelOffset.setRight(offset);
            }
        }
        
        // update offsets
        out.position(baseOffset + 4);
        for (AssetBundleEntryInfo entryInfo : entryInfos) {
            entryInfo.write(out);
        }
    }
}
