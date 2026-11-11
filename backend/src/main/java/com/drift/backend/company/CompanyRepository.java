package com.drift.backend.company;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanyRepository extends JpaRepository<Company, Long> {

	Optional<Company> findByCode(String code);

	@org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT company FROM Company company WHERE company.id = :id")
	Optional<Company> findForSignupReplacement(@Param("id") Long id);

	@Query("SELECT company FROM Company company WHERE UPPER(company.code) = :code")
	Optional<Company> findByNormalisedCode(@Param("code") String code);

	List<Company> findByActiveTrueOrderByNameAscIdAsc();

	@Query("""
			SELECT company FROM Company company
			WHERE company.active = true
			  AND company.createdBy.id = :creatorId
			  AND NOT EXISTS (
			    SELECT account.id FROM com.drift.backend.account.UserAccount account
			    WHERE account.company = company
			  )
			ORDER BY company.name ASC, company.id ASC
			""")
	List<Company> findActiveEmptyCreatedBy(@Param("creatorId") Long creatorId);

	@Query("""
			SELECT company FROM Company company
			WHERE company.active = true
			  AND company.id <> :companyId
			  AND (:name = '' OR LOCATE(LOWER(:name), LOWER(company.name)) > 0)
			""")
	Page<Company> findActiveOtherCompanies(@Param("companyId") Long companyId, @Param("name") String name,
			Pageable pageable);
}
