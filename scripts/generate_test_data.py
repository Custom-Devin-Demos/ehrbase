#!/usr/bin/env python3
"""
Telco Data Pipeline: Test Data Generator

Generates realistic tower signal measurements, handoff events, and CDRs
for local development and testing.

Usage:
    python generate_test_data.py --towers 100 --hours 24 --output-dir ./test-data
"""

import argparse
import csv
import math
import os
import random
import time
import uuid
from datetime import datetime, timedelta

# Tower configuration ranges
REGIONS = ["NORTHEAST", "SOUTHEAST", "MIDWEST", "SOUTHWEST", "WEST"]
MARKETS = ["NYC", "BOS", "MIA", "ATL", "CHI", "DFW", "DEN", "LAX", "SEA", "SFO"]
MORPHOLOGIES = ["DENSE_URBAN", "URBAN", "SUBURBAN", "RURAL", "HIGHWAY"]
TOWER_TYPES = ["MACRO", "MICRO", "SMALL_CELL", "DAS"]
TECHNOLOGIES = ["LTE", "5G_NR", "UMTS"]
FREQUENCY_BANDS = ["Band_2", "Band_4", "Band_7", "Band_12", "Band_25", "Band_66", "n71", "n77"]
CALL_TYPES = ["VOICE_MO", "VOICE_MT", "VOLTE_MO", "VOLTE_MT", "VOWIFI_MO"]
TERMINATION_CAUSES = ["NORMAL_CLEARING", "NORMAL_CLEARING", "NORMAL_CLEARING",
                      "RADIO_LINK_FAILURE", "HANDOFF_FAILURE", "CONGESTION",
                      "RESOURCE_UNAVAILABLE", "USER_INITIATED"]
HANDOFF_TYPES = ["INTRA_FREQ", "INTER_FREQ", "INTER_RAT", "INTRA_RAT"]
HANDOFF_CAUSES = ["SIGNAL_DEGRADATION", "LOAD_BALANCING", "COVERAGE_BASED",
                  "SPEED_BASED", "QUALITY_BASED"]
CODECS = ["AMR-WB", "AMR-NB", "EVS", "G.711"]


def generate_towers(num_towers):
    """Generate tower metadata with realistic configurations."""
    towers = []
    for i in range(num_towers):
        region = random.choice(REGIONS)
        lat = random.uniform(25.0, 48.0)
        lon = random.uniform(-122.0, -71.0)
        morphology = random.choice(MORPHOLOGIES)

        sectors = random.randint(1, 3) if morphology != "RURAL" else 1
        max_ues = {"MACRO": 200, "MICRO": 100, "SMALL_CELL": 50, "DAS": 150}

        tower_type = random.choice(TOWER_TYPES)
        tower = {
            "tower_id": f"310-260-{random.randint(1000, 9999)}-{random.randint(10000, 99999)}",
            "site_name": f"SITE_{region[:3]}_{i:04d}",
            "region": region,
            "market": random.choice(MARKETS),
            "latitude": lat,
            "longitude": lon,
            "morphology": morphology,
            "tower_type": tower_type,
            "sectors": sectors,
            "max_ues": max_ues[tower_type],
            "technology": random.choice(TECHNOLOGIES),
            "frequency_band": random.choice(FREQUENCY_BANDS),
        }
        towers.append(tower)
    return towers


def generate_signal_sample(tower, sector_id, timestamp_ms, hour):
    """Generate a single tower signal measurement."""
    # Time-of-day load pattern (peaks at 12 and 18)
    load_factor = 0.3 + 0.7 * (
        math.exp(-((hour - 12) ** 2) / 18) +
        0.8 * math.exp(-((hour - 18) ** 2) / 12)
    )

    morphology_rsrp = {
        "DENSE_URBAN": -95, "URBAN": -90, "SUBURBAN": -82,
        "RURAL": -78, "HIGHWAY": -88
    }
    base_rsrp = morphology_rsrp.get(tower["morphology"], -85)

    rsrp = base_rsrp + random.gauss(0, 5)
    rsrq = max(-20, min(-3, rsrp / 8 + random.gauss(0, 2)))
    sinr = max(-5, min(25, (rsrp + 120) / 3 + random.gauss(0, 3)))
    rssi = rsrp + random.uniform(5, 15)
    cqi = max(0, min(15, int((sinr + 5) / 2)))
    prb_util = min(100, max(0, load_factor * 70 + random.gauss(0, 10)))
    connected_ues = max(0, int(tower["max_ues"] * load_factor * random.uniform(0.5, 1.2)))
    dl_throughput = max(0, (100 - prb_util) * 1.5 + random.gauss(0, 10))
    ul_throughput = max(0, dl_throughput * 0.3 + random.gauss(0, 5))

    return (
        f"{uuid.uuid4()}|{tower['tower_id']}|{sector_id}|{timestamp_ms}|"
        f"{rssi:.2f}|{rsrp:.2f}|{rsrq:.2f}|{sinr:.2f}|{cqi}|"
        f"{connected_ues}|{prb_util:.1f}|{dl_throughput:.2f}|{ul_throughput:.2f}|"
        f"{tower['latitude']:.6f}|{tower['longitude']:.6f}|"
        f"{tower['technology']}|{tower['frequency_band']}|"
        f"{random.uniform(-110, -80):.2f}"
    )


def generate_handoff_event(towers, timestamp_ms):
    """Generate a handoff event between two towers."""
    src = random.choice(towers)
    tgt = random.choice([t for t in towers if t["tower_id"] != src["tower_id"]])

    result = random.choices(
        ["SUCCESS", "FAILURE_TIMEOUT", "FAILURE_RESOURCE", "FAILURE_CONFIG"],
        weights=[85, 7, 5, 3]
    )[0]

    src_rsrp = -90 + random.gauss(0, 8)
    tgt_rsrp = src_rsrp + random.uniform(-5, 15)
    velocity = random.choice([0, 5, 30, 60, 100, 120])
    duration_ms = random.randint(20, 500) if result == "SUCCESS" else random.randint(500, 5000)

    return "\t".join([
        str(uuid.uuid4()),
        f"IMSI_{random.randint(100000, 999999)}",
        str(timestamp_ms),
        src["tower_id"],
        tgt["tower_id"],
        random.choice(HANDOFF_TYPES),
        random.choice(HANDOFF_CAUSES),
        result,
        f"{src_rsrp:.1f}",
        f"{tgt_rsrp:.1f}",
        f"{velocity:.1f}",
        str(duration_ms),
        str(random.choice([True, False])).lower()
    ])


def generate_cdr(towers, timestamp_ms, hour):
    """Generate a Call Detail Record."""
    tower = random.choice(towers)
    duration = int(random.expovariate(1 / 180))  # Mean 3 minutes
    is_dropped = random.random() < 0.02  # 2% drop rate
    handoffs = random.randint(0, 5) if duration > 60 else 0
    mos = max(1.0, min(5.0, random.gauss(3.8, 0.5)))

    if is_dropped:
        mos = max(1.0, mos - 1.5)
        duration = max(5, int(duration * random.uniform(0.1, 0.7)))
        term_cause = random.choice(["RADIO_LINK_FAILURE", "HANDOFF_FAILURE", "CONGESTION"])
    else:
        term_cause = "NORMAL_CLEARING"

    end_ts = timestamp_ms + duration * 1000

    return ";".join([
        str(uuid.uuid4()),                       # cdr_id
        str(uuid.uuid4()),                       # call_id
        f"IMSI_{random.randint(100000, 999999)}", # caller
        f"IMSI_{random.randint(100000, 999999)}", # callee
        random.choice(CALL_TYPES),                # call_type
        str(timestamp_ms),                        # setup ts
        str(timestamp_ms + random.randint(1000, 5000)),  # connect ts
        str(end_ts),                              # end ts
        str(duration),                            # duration
        term_cause,                               # termination
        "1" if is_dropped else "0",               # dropped
        tower["tower_id"],                        # orig tower
        str(random.randint(0, 2)),                # orig sector
        random.choice(towers)["tower_id"],        # term tower
        str(random.randint(0, 2)),                # term sector
        str(handoffs),                            # handoff count
        str(random.randint(0, 1) if is_dropped else 0),  # failed handoffs
        f"{-85 + random.gauss(0, 10):.1f}",       # avg signal
        f"{-105 + random.gauss(0, 8):.1f}",       # min signal
        f"{15 + random.gauss(0, 5):.1f}",         # avg sinr
        f"{mos:.2f}",                             # MOS
        f"{random.uniform(0, 50):.1f}",           # jitter
        f"{random.uniform(0, 3):.2f}",            # packet loss
        random.choice(CODECS),                    # codec
        "HOME",                                   # roaming
        tower["technology"],                      # RAT start
        tower["technology"]                       # RAT end
    ])


def main():
    parser = argparse.ArgumentParser(description="Telco test data generator")
    parser.add_argument("--towers", type=int, default=50, help="Number of towers")
    parser.add_argument("--hours", type=int, default=24, help="Hours of data")
    parser.add_argument("--output-dir", type=str, default="./test-data")
    parser.add_argument("--signal-interval-sec", type=int, default=30,
                        help="Signal sample interval in seconds")
    args = parser.parse_args()

    os.makedirs(args.output_dir, exist_ok=True)

    print(f"Generating data: {args.towers} towers, {args.hours} hours")

    towers = generate_towers(args.towers)
    base_time = int((datetime.utcnow() - timedelta(hours=args.hours)).timestamp() * 1000)

    # Write tower metadata
    meta_path = os.path.join(args.output_dir, "tower_metadata.csv")
    with open(meta_path, "w") as f:
        writer = csv.DictWriter(f, fieldnames=list(towers[0].keys()))
        writer.writeheader()
        writer.writerows(towers)
    print(f"  Tower metadata: {meta_path} ({len(towers)} towers)")

    # Generate signal data
    signal_path = os.path.join(args.output_dir, "tower_signals.dat")
    signal_count = 0
    with open(signal_path, "w") as f:
        for h in range(args.hours):
            for minute in range(0, 60, args.signal_interval_sec // 60 + 1):
                ts = base_time + (h * 3600 + minute * 60) * 1000
                hour = (datetime.utcfromtimestamp(ts / 1000)).hour
                for tower in towers:
                    for sector in range(tower["sectors"]):
                        f.write(generate_signal_sample(tower, sector, ts, hour) + "\n")
                        signal_count += 1
    print(f"  Signals: {signal_path} ({signal_count:,} records)")

    # Generate handoff events
    handoff_path = os.path.join(args.output_dir, "handoff_events.dat")
    handoff_count = 0
    with open(handoff_path, "w") as f:
        for h in range(args.hours):
            events_per_hour = random.randint(
                len(towers) * 2, len(towers) * 10
            )
            for _ in range(events_per_hour):
                ts = base_time + h * 3600000 + random.randint(0, 3599999)
                f.write(generate_handoff_event(towers, ts) + "\n")
                handoff_count += 1
    print(f"  Handoffs: {handoff_path} ({handoff_count:,} records)")

    # Generate CDRs
    cdr_path = os.path.join(args.output_dir, "cdrs.dat")
    cdr_count = 0
    with open(cdr_path, "w") as f:
        for h in range(args.hours):
            hour = (datetime.utcfromtimestamp(
                (base_time + h * 3600000) / 1000
            )).hour
            calls_per_hour = int(len(towers) * 20 * (
                0.3 + 0.7 * math.exp(-((hour - 14) ** 2) / 20)
            ))
            for _ in range(calls_per_hour):
                ts = base_time + h * 3600000 + random.randint(0, 3599999)
                f.write(generate_cdr(towers, ts, hour) + "\n")
                cdr_count += 1
    print(f"  CDRs: {cdr_path} ({cdr_count:,} records)")

    print(f"\nTotal: {signal_count + handoff_count + cdr_count:,} records generated")


if __name__ == "__main__":
    main()
