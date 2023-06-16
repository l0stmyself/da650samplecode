package com.mongodb.tradecraft.social_server;

import java.util.ArrayList;
import java.util.Date;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.MongoClient;
import com.mongodb.MongoException;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.result.DeleteResult;

import static com.mongodb.client.model.Filters.*;

import static com.mongodb.client.model.Sorts.*;

import org.bson.conversions.Bson;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import com.mongodb.client.result.UpdateResult;
import static com.mongodb.client.model.Updates.*;


public class PostsDAL {

	/* Rather then have strings through the code and risk a typo
	always define your fieldnames as constants - perhaps in their own class */
	

	private static final int    FEEDPAGESIZE = 5;
	private static final String DISTPROCESS = "distprocess";
	private static final String DISTSTART = "diststart";
	private static final String POSTS = "posts";
	private static final String USER = "user";
	private static final String BUCKETSIZE = "bucketsize";
	private static final String DATE = "date";
	private static final String DISTRIBUTED = "distributed";
	private static final String DISTRIBUTING = "distributing";
	private static final String PUBLISHED = "published";
	private static final String SCHEMA_VERSION = "schemaVersion";
	private static final String STATUS = "status";
	private static final String POSTED_BY = "postedBy";
	private static final String DATE_CREATED = "dateCreated";
	private static final String TEXT = "text";
	private static final String _ID = "_id";
	private static final String POSTBUCKETS = "postbuckets";

	private UserDAL poster;
	private String text;
	private String status;
	private String lastError;
	private String postername;
	private ObjectId id;
	private boolean populated = false;
	private int schemaVersion = 2;
	private Date postDate;

	MongoClient mongoClient;
	MongoCollection<Document> postCollection;
	MongoCollection<Document> postBuckets;

	Logger logger;

	String getLastError() {
		return lastError;

	}

	ObjectId getId() {
		return id;
	}

	UserDAL getPoster() {
		if (poster == null) {
			return new UserDAL(mongoClient, postername);
		}
		return poster;
	}

	String getText() {
		return text;
	}

	boolean getPopulated() {
		return populated;
	}

	String getStatus() {
		return status;
	}

	String getPostername() {
		return postername;
	}

	Date getPostDate() {
		return postDate;
	}

	public PostsDAL(Document d, UserDAL poster) {
		this.poster = poster;
		this.postername = poster.getUsername();
		parseDocument(d);
	}

	private void parseDocument(Document doc) {
		populated = false;

		Integer version = doc.getInteger(SCHEMA_VERSION);

		if(version == null)
			return;

		switch (version) {
			case 1:
				parseDocumentV1(doc);
				break;
			case 2:
				parseDocumentV2(doc);
				break;
		}
	}

	private void parseDocumentV1(Document doc) {
		try {
			id = doc.getObjectId(_ID);
			text = doc.getString(TEXT);
			postDate = doc.getDate(DATE_CREATED);
			postername = doc.getString(POSTED_BY);
			
			} catch (Exception e) {
				lastError = e.getMessage();
				populated = false;
				return;
			}
		populated=true;
	}

	private void parseDocumentV2(Document doc) {
		try {
			id = doc.getObjectId(_ID);
			text = doc.getString(TEXT);
			postDate = doc.getDate(DATE_CREATED);
			postername = doc.getString(POSTED_BY);
			status = doc.getString(STATUS);
			
			} catch (Exception e) {
				lastError = e.getMessage();
				populated = false;
				return;
			}
		populated=true;
	}

	public PostsDAL(MongoClient mongoClient, UserDAL user, String postText, Date date) {
		this(mongoClient);
		this.text = postText;
		this.postDate = date;
		this.poster = user;
		this.lastError = "";
		this.status = "new";

	}

	public PostsDAL(MongoClient mongoClient) {
		logger = LoggerFactory.getLogger(WorkerTask.class);
		this.mongoClient = mongoClient;
		postCollection = mongoClient.getDatabase("social").getCollection(POSTS);
		postBuckets = mongoClient.getDatabase("social").getCollection(POSTBUCKETS);
	}

	boolean DeletePost(UserDAL user, String postId) {

		ObjectId oid = new ObjectId(postId);
		DeleteResult d = postCollection.deleteOne(and(eq("_id", oid), eq("postedBy", user.getUsername())));

		if(d.getDeletedCount() == 0)
		{
			return false;
		}

		user.updatePostCount(-1);
		return true;
	}


	boolean postToFollowers() {
		if(poster.isPopulated() == false) {
			this.lastError = poster.getLastError();
			
			return false;
	}
		
		Document post = new Document("postedBy",poster.getUsername());
		id = new ObjectId();
		post.append(_ID, id);
		post.append(SCHEMA_VERSION, schemaVersion);
		post.append(TEXT, text).append(DATE_CREATED,postDate);
		post.append(STATUS, PUBLISHED);

		try {
			postCollection.insertOne(post);
			poster.updatePostCount(1);
		} catch (MongoException e) {
			lastError = e.getMessage();
			return false;
		}
		return true;
	}


	// If we want X Posts and Bucket Size is Y (and larger than X) we want
	// At most two buckets (Deleted posts might change this but we havent done that
	// yet)

	ArrayList<PostsDAL> getFeed(UserDAL user, ObjectId fromId) {
		ArrayList<PostsDAL> rval = new ArrayList<PostsDAL>();
        FindIterable<Document> postBucketDocs = postBuckets
                .find(and(eq(USER, user.getUsername()), lt("posts._id", fromId))).sort(orderBy(descending("posts._id")))
                .limit(2);

        int count = 0;
        ObjectId oldest = new ObjectId();
        for (Document f : postBucketDocs) {
            if (count < FEEDPAGESIZE) {
                ArrayList<Document> postsInBucket = f.get(POSTS, new ArrayList<Document>());

                for (Document p : postsInBucket) {
                    if (count < 5) {
                        if (p.getObjectId(_ID).compareTo(fromId) < 0) {
                            p.append(SCHEMA_VERSION, f.getInteger(SCHEMA_VERSION));
                            rval.add(new PostsDAL(p, user));
                            oldest = p.getObjectId(_ID);
                            count++;
                        }
                    }
                }
            }
        }

        if (count < FEEDPAGESIZE) {
            // Get the highest we have and askl for more from the old store
            ArrayList<PostsDAL> morePosts = getFeedOld(user, oldest);
            rval.addAll(morePosts);
            if (rval.size() > 5) {
                rval = new ArrayList<PostsDAL>(rval.subList(0, FEEDPAGESIZE));
            }
        }
        return rval;

	}

	ArrayList<PostsDAL> getPostsForUser(UserDAL user) {
		//Limiting to 100 for simplicity now
		FindIterable<Document> myposts = postCollection.find(eq(POSTED_BY,user.getUsername()))
		.sort(orderBy(descending(_ID))).limit(100);
		
		ArrayList<PostsDAL> rval = new ArrayList<PostsDAL>();
		for(Document f : myposts) {
			rval.add(new PostsDAL(f,user));
		}
		return rval;
	}

	Integer countPostsByUser (String username) {

		int count = (int) postCollection.countDocuments(eq(POSTED_BY, username));
		return count;
	}

	boolean ClaimUndistributedPost(ObjectId claimant) {

		Date fiveminsago = new Date(new Date().getTime() - 300 * 1000);

		Bson query = or(and(eq(STATUS, DISTRIBUTING), lt(DISTSTART, fiveminsago)), eq(STATUS, PUBLISHED));
        Bson update = combine(set(STATUS, DISTRIBUTING), set(DISTPROCESS, claimant), set(DISTSTART, new Date()));
        // Sort so we get Distributing before Published - avoid orphanss
        FindOneAndUpdateOptions options = new FindOneAndUpdateOptions().sort(orderBy(ascending(STATUS)));

        Document doc = postCollection.findOneAndUpdate(query, update, options);
        if (doc == null) {
            return false;
        } // We didn't find one
        parseDocument(doc);
        return true;

	}

	public void fanOutToFollower(String follower) {

		 List<WriteModel<Document>> updates = new ArrayList<WriteModel<Document>>();
        LocalDate localDate = LocalDate.now();
        ZoneId defaultZoneId = ZoneId.systemDefault();
        Date today = Date.from(localDate.atStartOfDay(defaultZoneId).toInstant());

        Document post = new Document(POSTED_BY, getPoster().getUsername());
        post.append(_ID, id);
        post.append(TEXT, text);
        post.append(DATE_CREATED, postDate);

        // We want to set a schema version for this collection too
        // We should do then when posting to it
        Bson update = combine(addToSet(POSTS, post), max(SCHEMA_VERSION, 1), inc(BUCKETSIZE, 1));
        UpdateOptions updateOptions = new UpdateOptions().upsert(true);

        for (String follower : followers) {
            Bson query = and(eq(USER, follower), eq(DATE, today), lt(BUCKETSIZE, 200));

            updates.add(new UpdateOneModel<Document>(query, update, updateOptions));
        }

        // If we were to put a condition in to say don't push if already there the
        // We would trigger creation of a new bucket
        // Use a unique index on {user:1, posts.id:1 } to ensure uniqueness
        // between documents
        try {
            BulkWriteResult bulkWriteResult = postBuckets.bulkWrite(updates);
        } catch (MongoException e) {
            logger.error(e.getMessage());
        }


	}

	boolean MarkPostDistributed(ObjectId me) {
       	Bson query = and(eq(_ID, id), eq(DISTPROCESS, me));
        Bson update = set(STATUS, DISTRIBUTED);
        UpdateResult ur = postCollection.updateOne(query, update);
        return ur.getModifiedCount() == 1;

    }

}
