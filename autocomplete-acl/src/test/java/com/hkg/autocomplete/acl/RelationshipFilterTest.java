package com.hkg.autocomplete.acl;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.RetrievalSource;
import com.hkg.autocomplete.common.Suggestion;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RelationshipFilterTest {

    private static final TenantId T = TenantId.of("acme");

    private Suggestion sug(String entityId, Visibility v) {
        Candidate c = Candidate.builder()
                .entityId(entityId)
                .displayText(entityId)
                .tenantId(T)
                .family(EntityFamily.USER)
                .visibility(v)
                .retrievalScore(1.0)
                .source(RetrievalSource.FST_PRIMARY)
                .build();
        return Suggestion.of(c, 1.0);
    }

    @Test
    void selfAlwaysVisible() {
        assertThat(ViewerRelationship.SELF.canSee(Visibility.RESTRICTED)).isTrue();
        assertThat(ViewerRelationship.SELF.canSee(Visibility.PUBLIC)).isTrue();
    }

    @Test
    void blockedNeverVisible() {
        assertThat(ViewerRelationship.BLOCKED.canSee(Visibility.PUBLIC)).isFalse();
        assertThat(ViewerRelationship.BLOCKED.canSee(Visibility.INTERNAL)).isFalse();
        assertThat(ViewerRelationship.BLOCKED.canSee(Visibility.RESTRICTED)).isFalse();
    }

    @Test
    void friendCanSeeAll() {
        assertThat(ViewerRelationship.FRIEND.canSee(Visibility.PUBLIC)).isTrue();
        assertThat(ViewerRelationship.FRIEND.canSee(Visibility.INTERNAL)).isTrue();
        assertThat(ViewerRelationship.FRIEND.canSee(Visibility.RESTRICTED)).isTrue();
    }

    @Test
    void friendOfFriendCannotSeeRestricted() {
        assertThat(ViewerRelationship.FRIEND_OF_FRIEND.canSee(Visibility.PUBLIC)).isTrue();
        assertThat(ViewerRelationship.FRIEND_OF_FRIEND.canSee(Visibility.INTERNAL)).isTrue();
        assertThat(ViewerRelationship.FRIEND_OF_FRIEND.canSee(Visibility.RESTRICTED)).isFalse();
    }

    @Test
    void noneOnlySeesPublic() {
        assertThat(ViewerRelationship.NONE.canSee(Visibility.PUBLIC)).isTrue();
        assertThat(ViewerRelationship.NONE.canSee(Visibility.INTERNAL)).isFalse();
        assertThat(ViewerRelationship.NONE.canSee(Visibility.RESTRICTED)).isFalse();
    }

    @Test
    void filterDropsBlocked() {
        Map<String, ViewerRelationship> rels = new HashMap<>();
        rels.put("u_friend",  ViewerRelationship.FRIEND);
        rels.put("u_blocked", ViewerRelationship.BLOCKED);
        rels.put("u_public",  ViewerRelationship.NONE);

        RelationshipResolver resolver = (viewer, c) ->
                rels.getOrDefault(c.entityId(), ViewerRelationship.NONE);

        List<Suggestion> in = List.of(
                sug("u_friend",  Visibility.RESTRICTED),
                sug("u_blocked", Visibility.PUBLIC),     // blocked: dropped despite PUBLIC
                sug("u_public",  Visibility.PUBLIC),
                sug("u_public2", Visibility.INTERNAL)    // unmapped → NONE → drops INTERNAL
        );
        List<Suggestion> out = new RelationshipFilter(resolver).filter("u42", in);
        assertThat(out).extracting(s -> s.candidate().entityId())
                .containsExactly("u_friend", "u_public");
    }

    @Test
    void emptyInputReturnsEmpty() {
        RelationshipFilter f = new RelationshipFilter((viewer, c) -> ViewerRelationship.NONE);
        assertThat(f.filter("u42", List.of())).isEmpty();
    }
}
