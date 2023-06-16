package com.mongodb.tradecraft.social_server;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import com.mongodb.MongoClient;
import com.mongodb.MongoException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.operation.UpdateOperation;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;
import static com.mongodb.client.model.Projections.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class UserDAL {

	/*
	 * Rather then have strings through the code and risk a typo
	 * always define your fieldnames as constants - perhaps in their own class
	 */

	private static final String IS_NICKNAME = "isNickname";
	private static final String SCHEMA_VERSION = "schemaVersion";
	private static final String POSTED_BY = "postedBy";
	private static final String NICKNAMES = "nicknames";
	private static final String N_POSTS = "nPosts";
	private static final String DATE_CREATED = "dateCreated";
	private static final String _ID = "_id";
	private static final String FOLLOWS = "follows";
	private static final String CURRENT_NAME = "currentName";
	private static final String FOLLOW_SIZE = "followSize";
	private static final String ORIGINAL_NAME = "originalName";

	private Integer nPosts = null;
	private MongoClient mongoClient;
	private MongoCollection<Document> userCollection;
	private String lastError;
	private String username;
	private int schemaVersion = 4;
	private int doc_version = 0;

	private boolean populated;
	private Date accountCreated;

	private ArrayList<String> nicknames;
	private String currentName;
	private String originalName;


	// Memeber Acessors

	boolean isPopulated() {
		return populated;
	}

	String getLastError() {
		return lastError;
	}

	String getUsername() {
		return username;
	}

	Date getCreateDate() {
		return accountCreated;
	}

	Integer getPostCount() {

		if (isPopulated() == false) {
			return 0;
		}

		if (nPosts != null) {
			return nPosts;
		}

		int count = new PostsDAL(mongoClient).countPostsByUser(username);
		upgradePostCountSchema(count);
		nPosts = count;
		return count;

	}

	String getCurrentName(String username) {
		Document d = userCollection.find(eq(_ID, username)).projection(fields(include(CURRENT_NAME), exclude(_ID))).first();

		if (d == null) {
			return null;
		}

		String currentName = d.getString(CURRENT_NAME);
		if (currentName == null) {
			return username;
		}
		return currentName;
	}

	// Consstructors

	public UserDAL(MongoClient mongoClient, String username) {
		this(mongoClient);

		Document dbdata = userCollection.find(or(eq(_ID, username), eq(NICKNAMES, username))).first();

		if (dbdata != null) {
			parseDocument(dbdata);
		} else {
			lastError = String.format("User %s does not exist", username);
		}
	}

	public UserDAL(MongoClient mongoClient) {
		this.mongoClient = mongoClient;
		userCollection = mongoClient.getDatabase("social").getCollection("users");
		lastError = "";
	}

	// Functions

	// For a creator - we don't need to worry about old schema versions
	boolean createUser(String username) {
		// Just create it - and let _id deal with duplicates
		try {
			Document user = new Document(_ID, username);
			user.append(SCHEMA_VERSION, schemaVersion);
			user.append(DATE_CREATED, new Date());
			user.append(N_POSTS, 0);
			user.append(ORIGINAL_NAME, username);
			user.append(CURRENT_NAME, username);
			user.append(FOLLOW_SIZE, 0);

			userCollection.insertOne(user);
			this.username = username;
		} catch (MongoException e) {
			if (e.getCode() == 11000) {
				lastError = "User already exists"; // Duplicate key
			} else {
				lastError = e.getMessage();
			}
			return false;
		}
		lastError = "";
		return true;
	}

	private void parseDocument(Document doc) {

		populated = false;

		doc_version = doc.getInteger(SCHEMA_VERSION);

		if (doc_version == 0) {
			return;
		} // No version to read

		switch (doc_version) {
			case 1:
				parseDocumentV1(doc);
				break;
			case 2:
				parseDocumentV2(doc);
				break;
			case 3:
				parseDocumentV3(doc);
				break;
			case 4:
			    parseDocumentV4(doc);
				break;
		}
	}

	private void parseDocumentV1(Document doc) {

		// This is existing logic for schema version 1

		try {
			username = doc.getString(_ID);
			accountCreated = doc.getDate(DATE_CREATED);
		} catch (Exception e) {
			lastError = e.getMessage();
			populated = false;
			return;
		}

		populated = true;

	}

	private void parseDocumentV2(Document doc) {

		// This is new logic for schema version 2
		try {
			username = doc.getString(_ID);
			accountCreated = doc.getDate(DATE_CREATED);
			nPosts = doc.getInteger(N_POSTS);

		} catch (Exception e) {
			lastError = e.getMessage();
			populated = false;
			return;
		}

		populated = true;
	}

	private void parseDocumentV3(Document doc) {

		// This is for allowing users to change nickname schema change


		try {
			username = doc.getString(_ID);
			accountCreated = doc.getDate(DATE_CREATED);
			nPosts = doc.getInteger(N_POSTS);
			currentName = doc.getString(CURRENT_NAME);
			nicknames = doc.get(NICKNAMES, ArrayList.class);

		} catch (Exception e) {
			lastError = e.getMessage();
			populated = false;
			return;
		}

		populated = true;

	}

	private void parseDocumentV4(Document doc) {

		// This is for accounting for the overspill logic for following


		try {
			username = doc.getString(_ID);
			accountCreated = doc.getDate(DATE_CREATED);
			nPosts = doc.getInteger(N_POSTS);
			currentName = doc.getString(CURRENT_NAME);
			nicknames = doc.get(NICKNAMES, ArrayList.class);
			originalName = doc.getString(ORIGINAL_NAME);

		} catch (Exception e) {
			lastError = e.getMessage();
			populated = false;
			return;
		}

		populated = true;

	}

	boolean followUser(String star_name) {
		// Add someone new I am following , /We want to do an addToSet

		UserDAL star = new UserDAL(mongoClient, star_name);
		if (star.isPopulated() == false) {
			lastError = star.getLastError();
			return false;
		}

		if (originalName == null) {
			Document changes = new Document(ORIGINAL_NAME, "$_id").append(SCHEMA_VERSION, new Document("$max", Arrays.asList("$schemaVersion", 4))).append(FOLLOW_SIZE, new Document("$size", eq("$ifNull", Arrays.asList("$follows", Arrays.asList()))));

			List<Document> updates = Arrays.asList(new Document("$addFields", changes));

			UpdateResult u = userCollection.updateOne(eq(_ID, username), updates);
		}

		// Now let's use an automatic overspill to limit the array size

		Bson query = and(eq(ORIGINAL_NAME, username), lt(FOLLOW_SIZE, 6));
		Bson update = combine(inc(FOLLOW_SIZE, 1), addToSet(FOLLOWS, star.getUsername()));

		UpdateOptions options = new UpdateOptions().upsert(true);

		try {
			
			UpdateResult mb = userCollection.updateOne(query, update, options);
			
		} catch (MongoException e) {
			if (e.getCode() == 11000) {
                lastError = "Already following";
            } else {
                lastError = e.getMessage();
            }
            return false;
		}
		return true;
	}

	boolean unFollowUser(String star_name) {
		UserDAL star = new UserDAL(mongoClient, star_name);
		if (star.isPopulated() == false) {
			lastError = star.getLastError();
			return false;
		}

		// Do an pull to remove them - N.B relying on retryable writes here.
		try {
			UpdateResult r = userCollection.updateOne(eq(_ID, username), pull(FOLLOWS, star.username));
		} catch (MongoException e) {
			lastError = e.getMessage();
			return false;
		}
		return true;
	}

	// Followers means finding everyone following this person
	// As we store as follows
	ArrayList<String> getFollowers() {
		if (isPopulated() == false) {
			return null;
		}
		FindIterable<Document> myFollowers = userCollection.find(eq(FOLLOWS, username))
				.projection(fields(include(_ID)));
		ArrayList<String> rval = new ArrayList<String>();
		for (Document f : myFollowers) {
			rval.add(getCurrentName(f.getString(_ID)));
		}
		return rval;
	}

	// Followers means finding everyone following this person
	// As we store as follows
	int getFollowersCount() {
		if (isPopulated() == false) {
			return 0;
		}
		int count = (int) userCollection.countDocuments(eq(FOLLOWS, username));

		return count;
	}

	// Following means everyone we follows
	// As we store as follows
	ArrayList<String> getFollowing() {
		if (isPopulated() == false) {
			return null;
		}

		FindIterable<Document> iFollow = userCollection.find(or(eq(_ID, username), eq(ORIGINAL_NAME, username))).projection(fields(include(FOLLOWS)));

		ArrayList<String> following = new ArrayList<String>();

		for(Document d: iFollow) {
			following.addAll(d.get(FOLLOWS, new ArrayList<String>()));
		}

		return following;

	}

	// Followers means finding everyone following this person
	// As we store as follows
	int getFollowingCount() {
		return getFollowing().size();
	}

	// Followers means finding everyone following this person
	// As we store as follows
	ArrayList<PostsDAL> getPosts() {

		if (isPopulated() == false) {
			return null;
		}

		PostsDAL postDAL = new PostsDAL(mongoClient);
		ArrayList<PostsDAL> rval = postDAL.getPostsForUser(this);
		return rval;

	}

	// Followers means finding everyone following this person
	// As we store as follows
	ArrayList<PostsDAL> getFeed(String from) {

		if (isPopulated() == false) {
			return null;
		}

		ObjectId fromId;
		if (from == null || from.isBlank()) {
			fromId = new ObjectId();
		} else {
			fromId = new ObjectId(from);
		}

		PostsDAL postsDAL = new PostsDAL(mongoClient);
		ArrayList<PostsDAL> rval = postsDAL.getFeed(this, fromId);

		return rval;

	}

	ArrayList<String> getCurrentNames(ArrayList<String> names) {
		ArrayList<String> rval = new ArrayList<String>();
		for (String name: names) {
			String current;
			current = getCurrentName(name);
			if (current != null) {
				rval.add(current);
			}
		}
		return rval;
	}

	boolean upgradePostCountSchema(int howMany) {

		UpdateResult u = userCollection.updateOne(and(eq(_ID, username), exists(N_POSTS, false)),
				combine(set(N_POSTS, howMany), max(SCHEMA_VERSION, 2)));

		return u.wasAcknowledged();
	}

	boolean updatePostCount(int delta) {
		UpdateResult u = userCollection.updateOne(and(eq(_ID, username), gte(SCHEMA_VERSION, 2)), inc(N_POSTS, delta));

		if (u.wasAcknowledged()) {
			nPosts = nPosts + delta;
			return true;
		}

		return false;
	}

	boolean changeNickname(String nickName)
	{
		if (isPopulated() == false) {
			return false;
		}

		UpdateResult u;

		// If this is already one of our names, we should just change currentname
        if (nickName == username || (nicknames != null && nicknames.contains(nickName))) {
            u = userCollection.updateOne(eq(_ID, username), set(CURRENT_NAME, nickName));
        } else {
            Document placeholder = new Document(_ID, nickName).append(SCHEMA_VERSION, 3).append(IS_NICKNAME, true);
            try {
                userCollection.insertOne(placeholder);
            } catch (MongoException e) {
                if (e.getCode() == 11000) {
                    lastError = "Username already in use"; // Duplicate key
                } else {
                    lastError = e.getMessage();
                }
                return false;
            }

            // Add our _id and this nickname as the first time we need to know _id too
            u = userCollection.updateOne(eq(_ID, username), combine(set(CURRENT_NAME, nickName),
                    addEachToSet(NICKNAMES, Arrays.asList(nickName, username)), max(SCHEMA_VERSION, 3)));

        }

		if(u.wasAcknowledged()) {
			currentName = nickName;
			if (nicknames == null) {
				nicknames = new ArrayList<String>();
			}
			if (nicknames.contains(nickName) == false){
				nicknames.add(nickName);
			}
			return true;
		}

		return false;
	}

}
