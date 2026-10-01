package com.ie.evalos.chat.live;

import com.ie.evalos.domain.CaseOwned;
import com.ie.evalos.domain.DraftComment;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.stereotype.Component;

/**
 * Unit 70 §1.2: <strong>Hibernate, not per call site.</strong> Every insert, update and delete of a
 * {@link CaseOwned} row (and a {@link DraftComment}, resolved through its document) tells
 * {@link CaseLive} inside the transaction; {@code CaseLive} publishes after commit.
 *
 * <p>JPQL bulk and native writes bypass these listeners: any such write to a case-owned table
 * calls {@link CaseLive#touched} itself ({@code PayoutService} does, for its two).
 */
@Component
class CaseLiveHibernate implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

	private final EntityManagerFactory emf;
	private final CaseLive live;

	CaseLiveHibernate(EntityManagerFactory emf, CaseLive live) {
		this.emf = emf;
		this.live = live;
	}

	@PostConstruct
	void register() {
		EventListenerRegistry registry = emf.unwrap(SessionFactoryImplementor.class).getServiceRegistry()
				.getService(EventListenerRegistry.class);
		registry.appendListeners(EventType.POST_INSERT, this);
		registry.appendListeners(EventType.POST_UPDATE, this);
		registry.appendListeners(EventType.POST_DELETE, this);
	}

	@Override
	public void onPostInsert(PostInsertEvent event) {
		seen(event.getEntity());
	}

	@Override
	public void onPostUpdate(PostUpdateEvent event) {
		seen(event.getEntity());
	}

	@Override
	public void onPostDelete(PostDeleteEvent event) {
		seen(event.getEntity());
	}

	private void seen(Object entity) {
		if (entity instanceof CaseOwned owned) {
			live.touched(owned.getBrandId(), owned.liveCaseId());
		}
		else if (entity instanceof DraftComment comment) {
			live.touchedDocument(comment.getBrandId(), comment.getDocumentId());
		}
	}

	/** In-transaction, not post-commit: {@code CaseLive} already defers to after commit. */
	@Override
	public boolean requiresPostCommitHandling(EntityPersister persister) {
		return false;
	}
}
