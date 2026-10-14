package io.mateu.ecdemo1.mapping.store;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PartnerProfileRepository extends JpaRepository<PartnerProfile, String> {

    /** The partner a PMS profile is — the reverse of the equivalence. */
    java.util.Optional<PartnerProfile> findFirstByPmsProfileId(String pmsProfileId);

    /** {@code pattern} is a lower-case LIKE pattern escaped with '\'. */
    @Query(value = """
            select p from PartnerProfile p
            where lower(concat(coalesce(p.partnerCode, ''), ' ', coalesce(p.profileType, ''), ' ', coalesce(p.pmsProfileId, '')))
                  like :pattern escape '\\'
            order by p.partnerCode
            """, countQuery = """
            select count(p) from PartnerProfile p
            where lower(concat(coalesce(p.partnerCode, ''), ' ', coalesce(p.profileType, ''), ' ', coalesce(p.pmsProfileId, '')))
                  like :pattern escape '\\'
            """)
    Page<PartnerProfile> search(@Param("pattern") String pattern, Pageable pageable);
}
