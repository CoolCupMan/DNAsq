package com.coolcupman.dnasq.core

/** Raw-data file layouts produced by consumer and clinical DNA testing laboratories. */
enum class DnaFileFormat(val displayName: String) {
    /** `rsid  chromosome  position  genotype` (tab separated, `#` comments). */
    TWENTY_THREE_AND_ME("23andMe raw data (TXT)"),

    /** `rsid  chromosome  position  allele1  allele2` (tab separated, `#` comments). */
    ANCESTRY_DNA("AncestryDNA raw data (TXT)"),

    /** `"RSID","CHROMOSOME","POSITION","RESULT"` (quoted CSV). Used by MyHeritage and FamilyTreeDNA. */
    QUOTED_CSV("MyHeritage / FamilyTreeDNA raw data (CSV)"),

    /** Variant Call Format produced by whole-genome / exome sequencing labs and sequencer pipelines. */
    VCF("Variant Call Format (VCF)"),
}

/**
 * One build flavor per laboratory family. Each flavor ships with its own application id
 * (so several can be installed side by side) and its own version code / name suffix.
 *
 * [code] must stay stable forever: it is embedded in version codes and sealed templates.
 */
enum class LabProfile(
    val code: Int,
    val flavorName: String,
    val displayName: String,
    val formats: Set<DnaFileFormat>,
) {
    UNIVERSAL(0, "universal", "All laboratories", DnaFileFormat.entries.toSet()),
    TWENTY_THREE_AND_ME(1, "ttam", "23andMe", setOf(DnaFileFormat.TWENTY_THREE_AND_ME)),
    ANCESTRY_DNA(2, "ancestry", "AncestryDNA", setOf(DnaFileFormat.ANCESTRY_DNA)),
    MY_HERITAGE(3, "myheritage", "MyHeritage DNA", setOf(DnaFileFormat.QUOTED_CSV)),
    FAMILY_TREE_DNA(4, "ftdna", "FamilyTreeDNA", setOf(DnaFileFormat.QUOTED_CSV)),
    SEQUENCING(5, "wgs", "Sequencing labs & devices (VCF)", setOf(DnaFileFormat.VCF));

    companion object {
        fun fromFlavor(name: String): LabProfile =
            entries.firstOrNull { it.flavorName == name }
                ?: throw IllegalArgumentException("Unknown lab flavor: $name")

        fun fromCode(code: Int): LabProfile =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown lab code: $code")
    }
}
