package com.capitalone.calc.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Lives in calc-app's tests, where every tenant module is on the classpath. */
@AnalyzeClasses(packages = "com.capitalone.calc")
class TenantIsolationArchTest {

    @ArchTest
    static final ArchRule tenants_do_not_depend_on_each_other =
            slices().matching("com.capitalone.calc.tenant.(*)..").should().notDependOnEachOther();

    @ArchTest
    static final ArchRule tenants_depend_only_on_the_contract =
            classes().that().resideInAPackage("com.capitalone.calc.tenant..")
                    .should().onlyDependOnClassesThat()
                    .resideInAnyPackage("com.capitalone.calc.spi..", "com.capitalone.calc.tenant..", "java..");

    @ArchTest
    static final ArchRule shared_code_does_not_name_tenants =
            noClasses().that().resideOutsideOfPackage("com.capitalone.calc.tenant..")
                    .should().dependOnClassesThat().resideInAPackage("com.capitalone.calc.tenant..");

    @ArchTest
    static final ArchRule contract_depends_on_the_jdk_only =
            classes().that().resideInAPackage("com.capitalone.calc.spi..")
                    .should().onlyDependOnClassesThat().resideInAnyPackage("com.capitalone.calc.spi..", "java..");
}
