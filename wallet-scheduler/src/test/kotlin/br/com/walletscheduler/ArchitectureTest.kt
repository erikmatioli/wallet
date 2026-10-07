package br.com.walletscheduler

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * Same hexagonal rules as wallet-core and pix-service: dependencies always point inwards. ArchUnit
 * reads bytecode, so it checks Kotlin exactly as it checks Java.
 */
@AnalyzeClasses(packages = ["br.com.walletscheduler"], importOptions = [ImportOption.DoNotIncludeTests::class])
class ArchitectureTest {

    companion object {
        @JvmField
        @ArchTest
        val domainIsPure: ArchRule = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..application..", "..adapter..", "..config..",
                "org.springframework..", "jakarta..", "tools.jackson..", "io.opentelemetry..",
            )

        @JvmField
        @ArchTest
        val applicationIsFrameworkFree: ArchRule = noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..adapter..", "..config..", "org.springframework..", "jakarta..", "tools.jackson..",
                "io.opentelemetry..",
            )

        @JvmField
        @ArchTest
        val inboundAdaptersDoNotTouchOutboundAdapters: ArchRule = noClasses().that().resideInAPackage("..adapter.in..")
            .should().dependOnClassesThat().resideInAPackage("..adapter.out..")

        @JvmField
        @ArchTest
        val outboundAdaptersDoNotTouchInboundAdapters: ArchRule = noClasses().that().resideInAPackage("..adapter.out..")
            .should().dependOnClassesThat().resideInAPackage("..adapter.in..")
    }
}
