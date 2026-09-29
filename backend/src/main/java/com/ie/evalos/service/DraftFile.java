package com.ie.evalos.service;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.web.multipart.MultipartFile;

/** One uploaded part of a draft version (Unit 58), already sniffed by the controller. */
public record DraftFile(String filename, long size, InputStream body) {

	public static DraftFile of(MultipartFile part) throws IOException {
		return new DraftFile(part.getOriginalFilename(), part.getSize(), part.getInputStream());
	}
}
