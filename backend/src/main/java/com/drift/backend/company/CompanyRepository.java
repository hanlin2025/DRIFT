package com.drift.backend.company;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanyRepository extends JpaRepository<Company, Long> {

	Optional<Company> findByCode(String code);

	@Query("""
			SELECT company FROM Company company
			WHERE company.active = true
			  AND company.id <> :companyId
			  AND (:name = '' OR LOCATE(LOWER(:name), LOWER(company.name)) > 0)
			""")
	Page<Company> findActiveOtherCompanies(@Param("companyId") Long companyId, @Param("name") String name,
			Pageable pageable);
}
