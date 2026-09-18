package com.ie.evalos.web;

import java.nio.file.Files;
import java.nio.file.Path;

import com.ie.evalos.integration.DocumentStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves a locally stored document — <strong>development only, and it does not exist otherwise</strong>.
 *
 * <p><strong>This is the local stand-in for an S3 presigned URL</strong>, and it mirrors the two
 * properties that make one safe: the token is a capability that expires after five minutes
 * ({@code DocumentStore.READ_WINDOW}), and the response is always
 * {@code Content-Disposition: attachment} so a file that got past the upload sniffer still has no
 * origin to execute in.
 *
 * <p><strong>{@code @ConditionalOnProperty} rather than a runtime check.</strong> With
 * {@code evalos.s3.local-dir} blank — which is what {@code application.yml} and
 * {@code application-prod.yml} both leave it as — this bean is never created and the route simply
 * is not mapped. A 404 from a route that does not exist is a stronger guarantee than a 403 from
 * one that does, and {@code DocumentStore} additionally refuses to start if that property is set
 * outside the {@code local} profile.
 *
 * <p><strong>Unauthenticated on purpose</strong>, exactly as a presigned S3 URL is: the token is
 * the credential. That is also the reason it may never reach a deployment, and the two guards above
 * are what stop it.
 */
@RestController
@RequestMapping("/api/local-documents")
@ConditionalOnProperty(name = "evalos.s3.local-dir")
public class LocalDocumentController {

	private final DocumentStore store;

	LocalDocumentController(DocumentStore store) {
		this.store = store;
	}

	@GetMapping("/{token}")
	public ResponseEntity<Resource> read(@PathVariable String token) {
		return store.resolveLocalRead(token)
				.map(LocalDocumentController::attachment)
				// One answer for "no such token", "expired" and "the file is gone". They are the
				// same to the reader, and distinguishing them would tell a guesser which tokens
				// once existed.
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	private static ResponseEntity<Resource> attachment(Path file) {
		long length;
		try {
			length = Files.size(file);
		}
		catch (java.io.IOException ex) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_OCTET_STREAM)
				.contentLength(length)
				// The filename is the key's last segment, a UUID — never client-supplied text in a
				// response header, which is the same rule the presigned path follows.
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment")
				.body(new FileSystemResource(file));
	}
}
