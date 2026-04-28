package com.hkg.autocomplete.acl;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.Suggestion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Post-filter that drops candidates whose visibility forbids them
 * given the viewer-candidate relationship.
 *
 * <p>Runs after the reranker, before diversification, in the same
 * place as {@link PrincipalSet}-based post-filter. Production typeahead
 * stacks both: principal-based first (cheap), relationship-based
 * second (graph read, more expensive but cached).
 *
 * <p>Implementations of this filter <strong>must</strong> consult the
 * graph for every candidate — the production-canonical "Meta late
 * privacy validation". The retrieval layer can over-fetch (5-10×) so
 * the post-filter has enough survivors to fill the displayed top-K.
 */
public final class RelationshipFilter {

    private final RelationshipResolver resolver;

    public RelationshipFilter(RelationshipResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /** @return suggestions whose candidate the viewer is allowed to see. */
    public List<Suggestion> filter(String viewerUserId, List<Suggestion> in) {
        Objects.requireNonNull(viewerUserId, "viewerUserId");
        if (in.isEmpty()) return List.of();
        List<Suggestion> out = new ArrayList<>(in.size());
        for (Suggestion s : in) {
            Candidate c = s.candidate();
            ViewerRelationship rel = resolver.resolve(viewerUserId, c);
            if (rel.canSee(c.visibility())) {
                out.add(s);
            }
        }
        return out;
    }
}
