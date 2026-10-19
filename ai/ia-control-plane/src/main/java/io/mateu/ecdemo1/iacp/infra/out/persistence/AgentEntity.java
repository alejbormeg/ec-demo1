package io.mateu.ecdemo1.iacp.infra.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * The MCP and RAG references are stored as comma-separated ids in one column rather than as join
 * tables.
 *
 * <p>Not laziness: they are an ordered list of opaque ids that is only ever read whole, by one
 * query, to build one agent's configuration. A join table would buy referential integrity that
 * this design explicitly does not want — deleting an MCP server must be allowed, and the agent
 * degrades — and would cost a second query on the one path that has to stay cheap.
 */
@Entity
@Table(name = "agent")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class AgentEntity {

    @Id
    String id;
    @Column(nullable = false)
    String name;
    @Column(length = 32768, nullable = false)
    String systemPrompt;
    @Column(nullable = false)
    String llmId;
    @Column(length = 4096)
    String mcpIds;
    @Column(length = 4096)
    String ragIds;
    /** The agents this one may call over A2A. Same shape as the two above, for the same reason. */
    @Column(length = 4096)
    String peerAgentIds;
    @Column(length = 2048)
    String description;
    @Column(columnDefinition = "text")
    String outputSchema;
    boolean enabled;
    LocalDateTime created;
}
