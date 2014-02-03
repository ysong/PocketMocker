package edu.buffalo.cse.blue.pocketmocker.models;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

public class CurrentRecordingManager extends ModelManager {

	private static CurrentRecordingManager sInstance;

	public static CurrentRecordingManager getInstance(Context c) {
		if (sInstance == null) {
			sInstance = new CurrentRecordingManager(c);
		}
		return sInstance;
	}

	protected CurrentRecordingManager(Context c) {
		super(c);
	}

	// We're just going to use an append-only table so we can safely ignore sync
	// issues
	private static int index = 0;
	public static final String TABLE_NAME = "current_rec_id";
	public static final String COL_REC_ID = "rec_id";
	public static final int COL_REC_ID_INDEX = index++;
	public static final String COL_TIMESTAMP = "timestamp";
	public static final int COL_TIMESTAMP_INDEX = index++;
	public static final String CREATE_TABLE_CMD = Model.CREATE_TABLE + TABLE_NAME
			+ Model.OPEN_PAREN + COL_REC_ID + Model.INT + Model.PK + Model.COMMA + COL_TIMESTAMP
			+ Model.INT + Model.CLOSE_PAREN;
	public static final String DROP_TABLE_CMD = Model.dropTable(TABLE_NAME);

	public void setCurrentRecordingId(long id) {
		ContentValues values = new ContentValues();
		values.put(COL_REC_ID, id);
		values.put(COL_TIMESTAMP, System.currentTimeMillis());
		SQLiteDatabase sql = db.getWritableDatabase();
		sql.insert(TABLE_NAME, null, values);
		sql.close();
	}

	public long getCurrentRecordingId() {
		SQLiteDatabase sql = db.getReadableDatabase();
		Cursor cursor = sql.rawQuery("SELECT * FROM " + TABLE_NAME + " ORDER BY " + COL_TIMESTAMP
				+ " DESC LIMIT 1", null);
		if (cursor != null) {
			cursor.moveToFirst();
		}
		long recId = this.getLong(cursor, COL_REC_ID_INDEX);
		sql.close();
		return recId;
	}

}
