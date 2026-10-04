package br.com.walletpix.service;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Same hexagonal rules as wallet-core's ArchitectureTest: dependencies always point inwards. */
@AnalyzeClasses(packages = "br.com.walletpix.service", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsPure = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..application..", "..adapter..", "..config..", "br.com.walletpix.messages..",
                    "org.springframework..", "jakarta..", "tools.jackson..", "com.fasterxml..",
                    "software.amazon..", "io.opentelemetry..");

    /** The use cases speak the message contracts (pix-messages), but no framework, bus SDK or HTTP client. */
    @ArchTest
    static final ArchRule applicationIsFrameworkFree = noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..adapter..", "..config..", "org.springframework..", "jakarta..", "tools.jackson..",
                    "software.amazon..", "io.opentelemetry..");

    @ArchTest
    static final ArchRule inboundAdaptersDoNotTouchOutboundAdapters = noClasses().that().resideInAPackage("..adapter.in..")
            .should().dependOnClassesThat().resideInAPackage("..adapter.out..");

    @ArchTest
    static final ArchRule outboundAdaptersDoNotTouchInboundAdapters = noClasses().that().resideInAPackage("..adapter.out..")
            .should().dependOnClassesThat().resideInAPackage("..adapter.in..");
}
