package com.danawa.dsearch.indexer;

import com.danawa.dsearch.indexer.output.LogOutPutProcessOutput;
import com.github.fracpete.rsync4j.RSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class RsyncCopy extends Thread {


    Logger logger = LoggerFactory.getLogger(RsyncCopy.class);

    private String rsyncIp;
    private String path;
    private String rsyncPath;
    private Integer groupSeq;
    private String bwlimit;
    private boolean isCopy = true;
    private boolean isLink = false;

    public RsyncCopy(String rsyncIp, String rsyncPath, String path, String bwlimit, Integer groupSeq) {
        this.rsyncIp = rsyncIp;
        this.rsyncPath = rsyncPath;
        this.path = path;
        this.groupSeq = groupSeq;
        this.bwlimit = bwlimit;
    }

    public RsyncCopy(String rsyncIp, String rsyncPath, String path, String bwlimit, Integer groupSeq, boolean isLink) {
        this.rsyncIp = rsyncIp;
        this.rsyncPath = rsyncPath;
        this.path = path;
        this.groupSeq = groupSeq;
        this.bwlimit = bwlimit;
        this.isLink = isLink;
    }

    public boolean copyAsync() {
        return isCopy;
    }

    public void run() {

        logger.info("rsyncPath : {}", rsyncPath);
        logger.info("path : {}", path);
        logger.info("bwlimit : {}", bwlimit);
        String rsyncFileName = "";
        if(isLink){
             rsyncFileName = "linkExt_"+groupSeq;
        }else{
            rsyncFileName = "prodExt_"+groupSeq;
        }

        File file = new File(path +"/"+rsyncFileName);
        //File file = new File(rsyncPath +"\\"+rsyncFileName);

        if (file.exists()) {
            logger.info("기존 파일 삭제 : {}", file);
            file.delete();
        }
        logger.info("Rsync Command : {} ", "rsync -av --inplace --bwlimit="+bwlimit +" "+rsyncIp+"::" + rsyncPath+"/"+rsyncFileName + " " +path);
        RSync rsync = new RSync()
                //.source("C:\\Users\\admin\\Desktop\\indexFile\\sample\\prodExt_5")
                .source(rsyncIp+"::" + rsyncPath+"/"+rsyncFileName)
                .destination(path)
                .recursive(true)
                //.progress(true)
                .archive(true)
                .compress(true)
                .bwlimit(bwlimit)
                .inplace(true);

        LogOutPutProcessOutput output = new LogOutPutProcessOutput();
        try {
            output.monitor(rsync.builder());
        } catch (Exception e) {
            logger.error("Rsync Exception : {}", e);
            isCopy = false;
            throw new RuntimeException(e);
        }
    }

}
