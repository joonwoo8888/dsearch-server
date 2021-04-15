package com.danawa.dsearch.indexer.ingester;

import com.danawa.dsearch.indexer.FileIngester;
import com.danawa.dsearch.indexer.Utils;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.commons.lang.StringEscapeUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * Newline Delimiter Json 형식을 읽어들인다.
 * */
public class KonanIngester extends FileIngester {

    private Type entryType;
    private Gson gson;

    public KonanIngester(String filePath, String encoding, int bufferSize) {
        this(filePath, encoding, bufferSize, 0);
    }

    public KonanIngester(String filePath, String encoding, int bufferSize, int limitSize) {
        super(filePath, encoding, bufferSize, limitSize);
        gson = new Gson();
        entryType = new TypeToken<Map<String, Object>>() {}.getType();
    }

    @Override
    protected void initReader(BufferedReader reader) throws IOException {
        //do nothing
    }

    @Override
    protected Map<String, Object> parse(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            try {
                Map<String, Object> record = gson.fromJson(Utils.convertKonanToNdJson(line), entryType);
//                Map<String, Object> record = gson.fromJson(line, entryType);

                // HTML Decode 처리
                for (String key : record.keySet()){
                    Object value = record.get(key);
                    record.put(key,StringEscapeUtils.unescapeHtml(String.valueOf(value)));
                }

                //정상이면 리턴.
                return record;
            }catch(Exception e) {
                logger.error("parsing error : line= " + line, e);
            }
        }
        throw new IOException("EOF");
    }
}
