#!/usr/bin/env python3
"""Capstan failure simulator.

Emits a batch of failed recurring-mandate debits shaped like real Razorpay error
payloads, each carrying a hidden `oracle` block describing what would actually
have happened. The agent never sees the oracle; only the backtest harness does.

Payload shape follows Razorpay's published error object:

    {"error": {"code", "description", "field", "source", "step", "reason",
               "metadata": {"payment_id", "order_id"}}}

`code` is a coarse class (BAD_REQUEST_ERROR / GATEWAY_ERROR / SERVER_ERROR);
`reason` carries the specific machine-readable cause. Every reason string below
is taken from Razorpay's published lists -- the e-mandate subsequent-payment
errors for recurring debits, plus the general payments error list. None are
invented, because a Razorpay judge would recognise a fabricated one instantly.

  https://razorpay.com/docs/errors/
  https://razorpay.com/docs/errors/payments/list/
  https://razorpay.com/docs/payments/recurring-payments/emandate/errors/

Determinism is a hard requirement: the same --seed must produce a byte-identical
fixture, because Phase 09 deep-links a specific case id on stage. All randomness
flows from two seeded generators and nothing else.
"""

from __future__ import annotations

import argparse
import calendar
import json
import math
import random
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import numpy as np
from faker import Faker

# 3: customer.risk_flagged added for guardrail G10 and the UNDIAGNOSED clearance.
# 2: payload reshaped to the real Razorpay error object (code/reason split).
GENERATOR_VERSION = 4

CYCLE_DAYS = 35

TICKET_MEDIAN_PAISE = 49_900        # Rs 499
TICKET_SIGMA = 1.4
TICKET_CAP_PAISE = 1_499_900        # Rs 14,999
TICKET_FLOOR_PAISE = 4_900          # Rs 49

# Share of cases where the bank declined without telling Razorpay why, so the
# reason field is generic regardless of what actually went wrong. This is the
# population Tier 1 cannot resolve and where Tier 2/Tier 3 earn their place.
OBSCURED_SHARE = 0.15

# Of the obscured cases, the share whose bank narration still reveals the cause.
# The remainder are undecidable from the payload alone and cap achievable
# accuracy -- reported in the manifest rather than hidden.
OBSCURED_NARRATION_REVEALS = 0.75

# Within the revealing ones, the share phrased colloquially rather than in
# standard bank shorthand. Tier 2's rule list covers the shorthand; these are
# what Tier 3 has to generalise to.
COLLOQUIAL_SHARE = 0.50

# Share of non-RISK_BLOCKED customers carrying a risk flag, so G10 second clause
# does independent work and failing closed has a measurable cost.
RISK_FLAG_OTHER_SHARE = 0.02

DEBIT_ALREADY_SUCCEEDED = 6

CAUSE_SHARES = {
    "INSUFFICIENT_FUNDS": 0.32,
    "ISSUER_DECLINE_TEMPORARY": 0.12,
    "DO_NOT_HONOUR": 0.11,
    "CARD_EXPIRED": 0.09,
    "NETWORK_TIMEOUT": 0.08,
    "MANDATE_LIMIT_EXCEEDED": 0.07,
    "MANDATE_REVOKED": 0.06,
    "AUTHENTICATION_FAILED": 0.05,
    "ACCOUNT_CLOSED": 0.04,
    "RISK_BLOCKED": 0.03,
    "MANDATE_EXPIRED": 0.02,
    "TECHNICAL_DECLINE_UNKNOWN": 0.01,
}

# (code, reason, source, step, description) -- all real Razorpay values.
#
# Note MANDATE_REVOKED and MANDATE_EXPIRED both emit `mandate_not_active`:
# Razorpay does not distinguish them. That distinction is NOT resolvable from
# the payload and must not be asked of the classifier; the diagnosis layer
# resolves it deterministically from mandate.valid_until.
REASONS = {
    "INSUFFICIENT_FUNDS": [
        ("BAD_REQUEST_ERROR", "insufficient_funds", "customer", "payment_authorization",
         "Customer does not have sufficient funds in their account to complete the transaction"),
    ],
    "ISSUER_DECLINE_TEMPORARY": [
        ("GATEWAY_ERROR", "bank_technical_error", "bank", "payment_authorization",
         "The destination bank was facing technical problems while processing the payment"),
        ("GATEWAY_ERROR", "issuer_technical_error", "gateway", "payment_authorization",
         "A technical error occurred at the card issuer"),
    ],
    "NETWORK_TIMEOUT": [
        ("GATEWAY_ERROR", "gateway_technical_error", "gateway", "payment_authorization",
         "The gateway server encountered a technical error while processing the payment"),
        ("GATEWAY_ERROR", "request_timed_out", "gateway", "payment_authorization",
         "The request has timed out"),
        ("GATEWAY_ERROR", "invalid_response_from_gateway", "gateway", "payment_authorization",
         "An invalid response was received from the gateway"),
    ],
    "DO_NOT_HONOUR": [
        ("BAD_REQUEST_ERROR", "payment_failed", "bank", "payment_authorization",
         "The bank declined the payment; the exact reason was not communicated to Razorpay"),
        ("GATEWAY_ERROR", "payment_declined", "bank", "payment_authorization",
         "The payment has been declined"),
    ],
    "CARD_EXPIRED": [
        ("BAD_REQUEST_ERROR", "card_expired", "customer", "payment_authorization",
         "The card has expired"),
    ],
    "MANDATE_LIMIT_EXCEEDED": [
        ("BAD_REQUEST_ERROR", "transaction_limit_exceeded", "customer", "payment_authorization",
         "The customer has exceeded the credit or debit limit set on their account"),
    ],
    "MANDATE_REVOKED": [
        ("BAD_REQUEST_ERROR", "mandate_not_active", "business", "payment_authorization",
         "The registered mandate is no longer active"),
    ],
    "MANDATE_EXPIRED": [
        ("BAD_REQUEST_ERROR", "mandate_not_active", "business", "payment_authorization",
         "The registered mandate is no longer active"),
    ],
    "AUTHENTICATION_FAILED": [
        ("BAD_REQUEST_ERROR", "authentication_failed", "customer", "payment_authentication",
         "3D secure or OTP authentication failed"),
        ("BAD_REQUEST_ERROR", "incorrect_otp", "customer", "payment_authentication",
         "Customer entered an incorrect OTP"),
        ("BAD_REQUEST_ERROR", "otp_expired", "customer", "payment_authentication",
         "The OTP has expired"),
    ],
    "ACCOUNT_CLOSED": [
        ("BAD_REQUEST_ERROR", "bank_account_invalid", "bank", "payment_authorization",
         "The customer's bank account is either closed or no longer valid"),
    ],
    "RISK_BLOCKED": [
        ("BAD_REQUEST_ERROR", "payment_risk_check_failed", "gateway", "payment_authorization",
         "The payment was declined due to risk checks"),
        ("BAD_REQUEST_ERROR", "debit_instrument_blocked", "bank", "payment_authorization",
         "Withdrawals on the customer's account are temporarily blocked by the bank"),
    ],
    "TECHNICAL_DECLINE_UNKNOWN": [
        ("SERVER_ERROR", "server_error", "razorpay", "payment_authorization",
         "There was a technical error at Razorpay's server"),
    ],
}

# The bank declined without disclosing a reason. Indistinguishable, by design,
# from a genuine DO_NOT_HONOUR.
OBSCURED_REASONS = [
    ("BAD_REQUEST_ERROR", "payment_failed", "bank", "payment_authorization",
     "The bank declined the payment; the exact reason was not communicated to Razorpay"),
    ("GATEWAY_ERROR", "payment_declined", "bank", "payment_authorization",
     "The payment has been declined"),
]

# Standard bank shorthand. Tier 2's rule list is written against these.
NARRATIONS = {
    "INSUFFICIENT_FUNDS": ["INSUFF BAL", "LOW BALANCE", "INSUFFICIENT FUNDS AVL BAL 120.00", "NOT SUFF FUNDS"],
    "ISSUER_DECLINE_TEMPORARY": ["ISSUER DOWN", "BANK SERVER BUSY", "ISSUER UNAVAILABLE"],
    "NETWORK_TIMEOUT": ["AUTH TIMEOUT AT ACQUIRER", "NO RESP FRM BANK", "TIMEOUT"],
    "DO_NOT_HONOUR": ["TXN DECLINED BY ISSUER — DO NOT HONOUR", "DO NOT HONOUR", "DECLINE 05"],
    "CARD_EXPIRED": ["CARD EXPIRED", "EXP DATE INVALID"],
    "MANDATE_LIMIT_EXCEEDED": ["AMT > MANDATE CAP", "MNDT LIMIT EXCEEDED", "PER TXN LIMIT BREACH"],
    "MANDATE_REVOKED": ["MANDATE CANCELLED BY CUST", "UMN REVOKED"],
    "MANDATE_EXPIRED": ["MANDATE EXPIRED", "MNDT VALIDITY OVER"],
    "AUTHENTICATION_FAILED": ["AFA FAILED", "OTP NOT ENTERED"],
    "ACCOUNT_CLOSED": ["ACCOUNT CLOSED", "AC CLOSED"],
    "RISK_BLOCKED": ["RISK BLOCK", "FRAUD SUSPECT - BLOCKED"],
    "TECHNICAL_DECLINE_UNKNOWN": ["TECH DECLINE"],
}

# Conversational and Hinglish phrasings. Deliberately NOT enumerated in Tier 2's
# rules -- this is the population Tier 3 has to generalise to, and the honest
# basis for the claim that the LLM handles what the rules cannot.
NARRATIONS_COLLOQUIAL = {
    "INSUFFICIENT_FUNDS": ["balance nahi hai", "khate me paise kam hai", "acct bal short for debit"],
    "ISSUER_DECLINE_TEMPORARY": ["bank server down hai", "issuer side dikkat hai, baad me try karo"],
    "NETWORK_TIMEOUT": ["koi response nahi aaya bank se", "req hang ho gaya at switch"],
    "DO_NOT_HONOUR": ["bank ne mana kar diya", "declined, koi reason nahi diya"],
    "CARD_EXPIRED": ["card expire ho gaya", "card ki validity khatam"],
    "MANDATE_LIMIT_EXCEEDED": ["limit exceed hai", "mandate se zyada amount hai"],
    "MANDATE_REVOKED": ["mandate cancel kar diya customer ne", "autopay band kar diya"],
    "MANDATE_EXPIRED": ["mandate ki validity khatam ho gayi"],
    "AUTHENTICATION_FAILED": ["otp nahi aaya", "customer ne otp dala hi nahi"],
    "ACCOUNT_CLOSED": ["khata band hai", "account close ho chuka hai"],
    "RISK_BLOCKED": ["risk team ne block kiya", "fraud check me fail"],
    "TECHNICAL_DECLINE_UNKNOWN": ["technical issue tha"],
}

# Says nothing useful. An obscured case with one of these is undecidable from
# the payload; no tier can recover it and the manifest counts it as such.
GENERIC_NARRATIONS = ["DECLINED", "TXN FAILED", "REFER TO ISSUER", None, None]

# ---------------------------------------------------------------------------
# Holdout narration pool -- deliberately disjoint from the v1 pool above.
#
# batch_v1 and batch_holdout originally drew from the same constants, so 95.7%
# of holdout narrations appeared verbatim in v1 and Tier 2's regexes faced zero
# unseen strings. The holdout tested the policy engine but not rule
# generalisation, and reporting a diagnosis accuracy from it would have been a
# claim the data could not support.
#
# Caveat, stated rather than glossed: these strings were authored by the same
# person who wrote Tier 2's rules, which is weaker than an independent source.
# It is strictly better than an identical pool -- the rules match specific
# tokens, and strings built from different tokens genuinely exercise the
# fallback to Tier 3 -- but the measured overlap is reported, not assumed away.
NARRATIONS_HOLDOUT = {
    "INSUFFICIENT_FUNDS": ["BAL INSUFFICIENT", "FUNDS UNAVAILABLE", "DR BAL SHORT",
                           "MIN BAL BREACH ON DEBIT"],
    "ISSUER_DECLINE_TEMPORARY": ["ISSUER NOT REACHABLE", "HOST SYSTEM BUSY",
                                 "BANK OFFLINE RETRY LATER"],
    "NETWORK_TIMEOUT": ["SWITCH DID NOT RESPOND", "REQ EXPIRED AT NPCI", "NO ACK RECEIVED"],
    "DO_NOT_HONOUR": ["ISSUER REFUSED TXN", "DECLINED WITHOUT REASON CODE",
                      "RC 05 REFER CARD ISSUER"],
    "CARD_EXPIRED": ["VALIDITY DATE PASSED", "PLASTIC NO LONGER VALID"],
    "MANDATE_LIMIT_EXCEEDED": ["DEBIT ABOVE REGISTERED CEILING", "VALUE OVER UMN CAP",
                               "AMOUNT NOT PERMITTED BY MANDATE"],
    "MANDATE_REVOKED": ["AUTOPAY WITHDRAWN BY PAYER", "STANDING INSTRUCTION STOPPED"],
    "MANDATE_EXPIRED": ["SI TENURE COMPLETED", "REGISTRATION LAPSED"],
    "AUTHENTICATION_FAILED": ["2FA NOT COMPLETED", "CUSTOMER DID NOT AUTHORISE"],
    "ACCOUNT_CLOSED": ["NO SUCH ACCOUNT", "ACCT TERMINATED BY BANK"],
    "RISK_BLOCKED": ["TXN STOPPED BY MONITORING", "COMPLIANCE HOLD ON PAYER"],
    "TECHNICAL_DECLINE_UNKNOWN": ["PROCESSING ERROR AT BANK"],
}

NARRATIONS_COLLOQUIAL_HOLDOUT = {
    "INSUFFICIENT_FUNDS": ["paisa kam pad gaya", "account me itna balance nahi tha"],
    "ISSUER_DECLINE_TEMPORARY": ["bank ka system slow chal raha hai",
                                 "abhi bank se connect nahi ho pa raha"],
    "NETWORK_TIMEOUT": ["bahut der lag gayi, reply hi nahi mila",
                        "connection beech me toot gaya"],
    "DO_NOT_HONOUR": ["bank ne allow nahi kiya", "reject kar diya, kuch bataya nahi"],
    "CARD_EXPIRED": ["card ki date nikal gayi", "naya card lena padega"],
    "MANDATE_LIMIT_EXCEEDED": ["itna amount allowed nahi hai mandate me",
                               "cap se upar ja raha hai"],
    "MANDATE_REVOKED": ["customer ne autopay hata diya", "permission wapas le li"],
    "MANDATE_EXPIRED": ["mandate purana ho gaya, renew karna hoga"],
    "AUTHENTICATION_FAILED": ["verify nahi kar paya customer",
                              "authentication adhoora reh gaya"],
    "ACCOUNT_CLOSED": ["account ab exist nahi karta", "bank me khata hai hi nahi"],
    "RISK_BLOCKED": ["security wale ne rok diya", "monitoring me pakda gaya"],
    "TECHNICAL_DECLINE_UNKNOWN": ["system me kuch gadbad thi"],
}

GENERIC_NARRATIONS_HOLDOUT = ["NOT PROCESSED", "FAILED", "CONTACT BANK", None, None]

# Selected in main() from --narration-pool. choose_payload reads this.
POOLS = {
    "standard": NARRATIONS,
    "colloquial": NARRATIONS_COLLOQUIAL,
    "generic": GENERIC_NARRATIONS,
    "name": "v1",
}

RAILS = ["UPI_AUTOPAY", "CARD", "ENACH"]

SALARY_DAYS = [1, 1, 1, 1, 1, 2, 3, 5, 7, 25, 28, 30]


def det_uuid(rng: random.Random) -> str:
    """A v4-shaped UUID drawn from the seeded RNG (version/variant bits set by
    uuid.UUID itself), so fixtures are reproducible across regenerates."""
    return str(uuid.UUID(int=rng.getrandbits(128), version=4))


def iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def next_day_of_month(after: datetime, dom: int) -> datetime:
    year, month = after.year, after.month
    for _ in range(4):
        day = min(dom, calendar.monthrange(year, month)[1])
        candidate = datetime(year, month, day, tzinfo=timezone.utc)
        if candidate > after:
            return candidate
        month += 1
        if month > 12:
            month, year = 1, year + 1
    raise RuntimeError("could not find next day-of-month")


def sample_ticket_paise(nprng: np.random.Generator) -> int:
    mu = math.log(TICKET_MEDIAN_PAISE)
    while True:
        value = int(round(nprng.lognormal(mean=mu, sigma=TICKET_SIGMA)))
        if TICKET_FLOOR_PAISE <= value <= TICKET_CAP_PAISE:
            return value


def draw_causes(n: int, rng: random.Random) -> list[str]:
    names = list(CAUSE_SHARES)
    exact = [CAUSE_SHARES[c] * n for c in names]
    counts = [int(math.floor(v)) for v in exact]
    remainder = n - sum(counts)
    order = sorted(range(len(names)), key=lambda i: exact[i] - counts[i], reverse=True)
    for i in range(remainder):
        counts[order[i]] += 1
    causes = [c for c, k in zip(names, counts) for _ in range(k)]
    rng.shuffle(causes)
    return causes


def build_oracle(cause, rng, first_failed_at, salary_day, has_alternate_rail):
    oracle = {
        "recoverable": False,
        "recovery_window_start": None,
        "required_channel": "ANY",
        "nudge_sensitivity": 0.0,
        "attempt_success_prob": 1.0,
        "true_cause": cause,
        "true_state": None,
    }

    if cause == "INSUFFICIENT_FUNDS":
        payday = next_day_of_month(first_failed_at, salary_day)
        oracle.update(
            recoverable=True,
            recovery_window_start=payday + timedelta(hours=6, minutes=rng.randrange(0, 360)),
            required_channel="ANY",
            nudge_sensitivity=round(rng.uniform(0.20, 0.80), 3),
        )
    elif cause == "ISSUER_DECLINE_TEMPORARY":
        oracle.update(
            recoverable=True,
            recovery_window_start=first_failed_at + timedelta(hours=rng.randrange(2, 30)),
            required_channel="SAME_RAIL",
            attempt_success_prob=round(rng.uniform(0.35, 0.70), 3),
        )
    elif cause == "NETWORK_TIMEOUT":
        oracle.update(
            recoverable=True,
            required_channel="ANY",
            attempt_success_prob=round(rng.uniform(0.80, 0.95), 3),
        )
    elif cause == "DO_NOT_HONOUR":
        if rng.random() < 0.40:
            oracle.update(
                recoverable=True,
                recovery_window_start=first_failed_at + timedelta(hours=rng.randrange(12, 48)),
                required_channel="ANY",
                attempt_success_prob=round(rng.uniform(0.40, 0.70), 3),
            )
    elif cause in ("CARD_EXPIRED", "MANDATE_LIMIT_EXCEEDED", "MANDATE_EXPIRED"):
        oracle.update(
            recoverable=True,
            required_channel="REAUTH_REQUIRED",
            nudge_sensitivity=round(rng.uniform(0.25, 0.65), 3),
        )
    elif cause == "AUTHENTICATION_FAILED":
        if has_alternate_rail and rng.random() < 0.5:
            oracle.update(recoverable=True, required_channel="ALTERNATE_RAIL",
                          attempt_success_prob=round(rng.uniform(0.70, 0.95), 3))
        else:
            oracle.update(recoverable=True, required_channel="REAUTH_REQUIRED",
                          nudge_sensitivity=round(rng.uniform(0.25, 0.60), 3))
    elif cause == "TECHNICAL_DECLINE_UNKNOWN":
        if rng.random() < 0.5:
            oracle.update(
                recoverable=True,
                recovery_window_start=first_failed_at + timedelta(hours=rng.randrange(6, 36)),
                required_channel="ANY",
                attempt_success_prob=round(rng.uniform(0.50, 0.85), 3),
            )
    return oracle


def choose_payload(cause, rng, obscured):
    """Returns (code, reason, source, step, description, narration, decidable)."""
    if not obscured:
        code, reason, source, step, desc = rng.choice(REASONS[cause])
        narration = rng.choice(POOLS["standard"][cause])
        if rng.random() < 0.12:
            narration = None
        # The reason itself identifies the cause, so it stays decidable even
        # with no narration. MANDATE_* is decidable via mandate.valid_until.
        return code, reason, source, step, desc, narration, True

    code, reason, source, step, desc = rng.choice(OBSCURED_REASONS)
    if rng.random() < OBSCURED_NARRATION_REVEALS:
        pool = POOLS["colloquial"] if rng.random() < COLLOQUIAL_SHARE else POOLS["standard"]
        return code, reason, source, step, desc, rng.choice(pool[cause]), True

    # Generic reason plus a narration that says nothing: nothing in the payload
    # distinguishes this from a plain issuer decline.
    narration = rng.choice(POOLS["generic"])
    decidable = cause == "DO_NOT_HONOUR"
    return code, reason, source, step, desc, narration, decidable


def build_record(cause, rng, nprng, fake, cycle_start, cycle_end, obscured):
    customer_id = det_uuid(rng)
    mandate_id = det_uuid(rng)
    case_id = det_uuid(rng)

    first_failed_at = cycle_start + timedelta(
        hours=rng.randrange(0, 48), minutes=rng.randrange(0, 60)
    )
    salary_day = rng.choice(SALARY_DAYS)
    amount_paise = sample_ticket_paise(nprng)

    rail = rng.choice(RAILS)
    has_alternate = rng.random() < 0.40
    alternate_rail = rng.choice([r for r in RAILS if r != rail]) if has_alternate else None
    if cause == "AUTHENTICATION_FAILED" and not has_alternate and rng.random() < 0.5:
        alternate_rail = rng.choice([r for r in RAILS if r != rail])
        has_alternate = True

    if cause == "MANDATE_LIMIT_EXCEEDED":
        max_amount_paise = int(amount_paise * rng.uniform(0.40, 0.90))
    else:
        max_amount_paise = int(amount_paise * rng.uniform(1.20, 3.00))

    valid_from = cycle_start - timedelta(days=rng.randrange(30, 720))
    if cause == "MANDATE_EXPIRED":
        mandate_status = "EXPIRED"
        valid_until = first_failed_at - timedelta(days=rng.randrange(1, 20))
    elif cause == "MANDATE_REVOKED":
        mandate_status = "REVOKED"
        valid_until = cycle_start + timedelta(days=rng.randrange(60, 400))
    else:
        mandate_status = "ACTIVE"
        valid_until = cycle_start + timedelta(days=rng.randrange(60, 400))

    code, reason, source, step, desc, narration, decidable = choose_payload(cause, rng, obscured)
    oracle = build_oracle(cause, rng, first_failed_at, salary_day, has_alternate)

    return {
        "customer": {
            "id": customer_id,
            "external_ref": fake.bothify("CUST-####-????").upper(),
            "contact_opted_out": rng.random() < 0.08,
            "risk_flagged": False,   # set after generation, see mark_risk_flags
            "preferred_locale": "hi-IN" if rng.random() < 0.25 else "en-IN",
        },
        "mandate": {
            "id": mandate_id,
            "customer_id": customer_id,
            "rail": rail,
            "valid_from": iso(valid_from),
            "valid_until": iso(valid_until),
            "max_amount_paise": max_amount_paise,
            "status": mandate_status,
            "alternate_rail": alternate_rail,
        },
        "case": {
            "id": case_id,
            "mandate_id": mandate_id,
            "customer_id": customer_id,
            "amount_paise": amount_paise,
            "currency": "INR",
            "billing_cycle_end": iso(cycle_end),
            "first_failed_at": iso(first_failed_at),
            "raw_error_code": code,
            "raw_error_reason": reason,
            "raw_error_desc": desc,
            "raw_error_source": source,
            "raw_error_step": step,
            "bank_narration": narration,
        },
        "oracle": {
            "recoverable": oracle["recoverable"],
            "recovery_window_start": iso(oracle["recovery_window_start"]) if oracle["recovery_window_start"] else None,
            "required_channel": oracle["required_channel"],
            "nudge_sensitivity": oracle["nudge_sensitivity"],
            "attempt_success_prob": oracle["attempt_success_prob"],
            "true_cause": oracle["true_cause"],
            "true_state": oracle["true_state"],
        },
        "_decidable": decidable,
    }


def mark_risk_flags(records, rng):
    """Sets customer.risk_flagged. Returns (total_flagged, flagged_but_recoverable).

    Every RISK_BLOCKED customer is flagged, which is the obvious case. But if the
    flag *only* ever coincided with RISK_BLOCKED, guardrail G10's second clause
    would be redundant with its first and would never do independent work.

    So a small number of otherwise-recoverable customers are flagged too. Those
    are the honest cost of failing closed: cases a debit would have recovered,
    that the risk hold stops anyway. Phase 07 should report that number rather
    than hide it -- a safety control with no measured cost is not being measured.
    """
    for record in records:
        if record["oracle"]["true_cause"] == "RISK_BLOCKED":
            record["customer"]["risk_flagged"] = True
    flagged_by_cause = sum(1 for r in records if r["customer"]["risk_flagged"])

    candidates = [r for r in records
                  if r["oracle"]["true_cause"] != "RISK_BLOCKED" and r["oracle"]["recoverable"]]
    n_extra = min(max(1, round(len(records) * RISK_FLAG_OTHER_SHARE)), len(candidates))
    for record in rng.sample(candidates, n_extra):
        record["customer"]["risk_flagged"] = True

    return flagged_by_cause + n_extra, n_extra


def main() -> None:
    ap = argparse.ArgumentParser(description="Generate a synthetic Capstan failure batch.")
    ap.add_argument("--cases", type=int, default=300)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--cycle-start", default="2026-09-01")
    ap.add_argument("--out", required=True)
    ap.add_argument("--narration-pool", choices=["v1", "holdout"], default="v1",
                    help="which narration vocabulary to draw from; the holdout pool is "
                         "disjoint from v1 so Tier 2 rules face unseen strings")
    args = ap.parse_args()

    if args.narration_pool == "holdout":
        POOLS.update(standard=NARRATIONS_HOLDOUT, colloquial=NARRATIONS_COLLOQUIAL_HOLDOUT,
                     generic=GENERIC_NARRATIONS_HOLDOUT, name="holdout")

    rng = random.Random(args.seed)
    nprng = np.random.default_rng(args.seed)
    fake = Faker("en_IN")
    Faker.seed(args.seed)

    cycle_start = datetime.strptime(args.cycle_start, "%Y-%m-%d").replace(tzinfo=timezone.utc)
    cycle_end = cycle_start + timedelta(days=CYCLE_DAYS)

    causes = draw_causes(args.cases, rng)
    n_obscured = int(round(args.cases * OBSCURED_SHARE))
    obscured_idx = set(rng.sample(range(args.cases), n_obscured))

    records = [
        build_record(cause, rng, nprng, fake, cycle_start, cycle_end, i in obscured_idx)
        for i, cause in enumerate(causes)
    ]

    timeouts = [r for r in records if r["oracle"]["true_cause"] == "NETWORK_TIMEOUT"]
    n_marked = min(DEBIT_ALREADY_SUCCEEDED, len(timeouts))
    for r in timeouts[:n_marked]:
        r["oracle"]["true_state"] = "DEBIT_ALREADY_SUCCEEDED"
        r["oracle"]["recoverable"] = True
        r["oracle"]["attempt_success_prob"] = 1.0

    n_risk_flagged, n_flagged_recoverable = mark_risk_flags(records, rng)

    undecidable = [r for r in records if not r.pop("_decidable")]
    n_undecidable = len(undecidable)

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(records, indent=2, ensure_ascii=False), encoding="utf-8")

    amounts = sorted(r["case"]["amount_paise"] for r in records)
    realised: dict[str, int] = {}
    reasons_used: dict[str, int] = {}
    for r in records:
        realised[r["oracle"]["true_cause"]] = realised.get(r["oracle"]["true_cause"], 0) + 1
        rsn = r["case"]["raw_error_reason"]
        reasons_used[rsn] = reasons_used.get(rsn, 0) + 1

    def pct(p: float) -> int:
        return amounts[min(len(amounts) - 1, int(p * len(amounts)))]

    manifest = {
        "generator_version": GENERATOR_VERSION,
        "narration_pool": POOLS["name"],
        "seed": args.seed,
        "cases": args.cases,
        "cycle_start": iso(cycle_start),
        "billing_cycle_end": iso(cycle_end),
        "cycle_days": CYCLE_DAYS,
        "at_risk_paise": sum(amounts),
        "at_risk_rupees": round(sum(amounts) / 100, 2),
        "recoverable_by_oracle": sum(1 for r in records if r["oracle"]["recoverable"]),
        "debit_already_succeeded": n_marked,
        "risk_flagged_customers": n_risk_flagged,
        "risk_flagged_but_recoverable": n_flagged_recoverable,
        "error_taxonomy": {
            "shape": "razorpay error object: code (coarse class) + reason (specific cause)",
            "reasons_are_published_razorpay_values": True,
            "sources": [
                "https://razorpay.com/docs/errors/",
                "https://razorpay.com/docs/errors/payments/list/",
                "https://razorpay.com/docs/payments/recurring-payments/emandate/errors/",
            ],
            "note": "mandate_not_active covers both revoked and expired; Razorpay does "
                    "not distinguish them, so the diagnosis layer resolves it from "
                    "mandate.valid_until rather than asking the classifier to guess.",
        },
        "obscured": {
            "count": n_obscured,
            "share": OBSCURED_SHARE,
            "meaning": "bank declined without disclosing a reason; reason field is generic",
            "narration_reveals_share": OBSCURED_NARRATION_REVEALS,
            "colloquial_share_of_revealing": COLLOQUIAL_SHARE,
        },
        "undecidable_from_payload": n_undecidable,
        "accuracy_ceiling": round((args.cases - n_undecidable) / args.cases, 4),
        "ticket_distribution": {
            "kind": "lognormal",
            "median_paise": TICKET_MEDIAN_PAISE,
            "sigma": TICKET_SIGMA,
            "floor_paise": TICKET_FLOOR_PAISE,
            "cap_paise": TICKET_CAP_PAISE,
            "out_of_range_handling": "resampled, not clipped",
            "realised_percentiles_paise": {
                "p10": pct(0.10), "p50": pct(0.50), "p75": pct(0.75),
                "p90": pct(0.90), "p99": pct(0.99), "max": amounts[-1],
            },
        },
        "cause_distribution": {
            "target": CAUSE_SHARES,
            "realised_counts": dict(sorted(realised.items(), key=lambda kv: -kv[1])),
        },
        "reason_counts": dict(sorted(reasons_used.items(), key=lambda kv: -kv[1])),
    }
    manifest_path = out_path.with_name(out_path.stem + "_manifest.json")
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    print(f"wrote {len(records)} cases -> {out_path}")
    print(f"wrote manifest        -> {manifest_path}")
    print(f"at risk: Rs {manifest['at_risk_rupees']:,.2f}  "
          f"recoverable: {manifest['recoverable_by_oracle']}  "
          f"already-succeeded: {n_marked}")
    print(f"obscured: {n_obscured}  undecidable-from-payload: {n_undecidable}  "
          f"accuracy ceiling: {manifest['accuracy_ceiling']:.4f}")


if __name__ == "__main__":
    main()
