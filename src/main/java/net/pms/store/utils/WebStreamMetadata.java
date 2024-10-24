package net.pms.store.utils;


public record WebStreamMetadata(
	String URL,
	String LOGO_URL,
	String GENRE,
	String CONTENT_TYPE,
	Integer SAMPLE_RATE,
	Integer BITRATE,
	Integer TYPE) { }