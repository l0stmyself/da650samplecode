package com.mongodb.tradecraft.social_server;

import com.mongodb.MongoClient;

import java.util.ArrayList;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkerTask implements Runnable {

    Logger logger;
    int threadId;
    MongoClient mongoClient;

    WorkerTask(int threadid, MongoClient mongoClient) {
        logger = LoggerFactory.getLogger(WorkerTask.class);
        this.threadId = threadid;
        this.mongoClient = mongoClient;
    }

 

    public void run() {
        logger.info("Thread {} has started.", threadId);

        while(true){
            if (distributePost() == false) {
                try {
                    Thread.sleep(5000);
                }
                catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

    }

    boolean distributePost() {

        PostsDAL post = new PostsDAL(mongoClient);
        ObjectId me = new ObjectId();
    
        if (post.ClaimUndistributedPost(me)) {
            /*
                * We could have clever code to decide WHO to send it to here so it's not in the
                * DAL
                */
            ArrayList<String> followers = post.getPoster().getFollowers();
            post.fanOutToFollower(followers);
    
            post.MarkPostDistributed(me);
            return true;
        }
    
        return false;

    }
}