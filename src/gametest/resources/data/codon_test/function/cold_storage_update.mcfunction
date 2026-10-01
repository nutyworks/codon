# Persisted cold-world baseline, real Continue mutation/deletion, then true creation and unchanged stops.
say codon-cold-storage-baseline
scoreboard players add @s cold_storage_points 1
data modify storage codon_cold:acceptance changed set value 1
data remove storage codon_cold:acceptance removed
data modify entity @s Health set value 9.0f
say codon-cold-storage-changed
data modify storage codon_cold_new:target created set value 2
say codon-cold-storage-created
say codon-cold-storage-unchanged
