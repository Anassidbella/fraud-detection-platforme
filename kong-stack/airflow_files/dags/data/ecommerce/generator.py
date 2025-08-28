import pandas as pd
import numpy as np
import random
from datetime import datetime, timedelta
from tqdm import tqdm
import uuid

# --- CONFIGURATION V8.1: CORRECTED TIMESTAMP ---
CONFIG = {
    "NUM_RECORDS": 200000,
    "OUTPUT_FILENAME": "ecommerce_fraud_dataset_v8.1_final_challenge.csv",
    "ARCHETYPE_PROPORTIONS": {
        "legit_standard": 0.45, "legit_guest_checkout": 0.10, "legit_high_value": 0.05,
        "legit_international_gift": 0.05, "legit_user_on_vacation": 0.05, "legit_complex_gift": 0.05,
        "legit_spending_spike": 0.05, "legit_risky_micro_guest": 0.05, "fraud_account_takeover": 0.05,
        "fraud_card_testing": 0.05, "fraud_shipping_reship": 0.05,
    }
}

# --- STATIC LISTS (Unchanged) ---
COUNTRIES = ["US", "GB", "CA", "DE", "FR", "AU", "MA", "EG", "NG", "ZA", "TN"]
PRODUCT_CATEGORIES = ["electronics", "clothing", "books", "home_goods", "beauty", "toys", "sports", "digital_goods"]
CURRENCIES = ["USD", "EUR", "GBP", "MAD"]
PAYMENT_METHODS = ["credit_card", "paypal", "apple_pay", "google_pay"]
EMAIL_DOMAINS = {"legit": ["gmail.com", "yahoo.com", "outlook.com", "company.com"], "risky": ["yopmail.com", "temp-mail.org", "mailinator.com"]}

# --- HELPER FUNCTIONS (WITH THE FIX) ---
def generate_user_id(): return f"u_{str(uuid.uuid4().hex)[:12]}"
def generate_timestamp(start_date=datetime(2025, 1, 1), end_date=datetime(2025, 7, 22)):
    time_between_dates = end_date - start_date
    random_seconds = random.randint(0, int(time_between_dates.total_seconds()))
    # --- DEFINITIVE FIX: Corrected the format string from "%H M:%S" to "%H:%M:%S" ---
    return (start_date + timedelta(seconds=random_seconds)).strftime("%Y-%m-%d %H:%M:%S")

# --- TRANSACTION ARCHETYPES (Unchanged, they were already robust) ---
def generate_legit_risky_micro_guest():
    country = random.choice(COUNTRIES); email_domain = random.choices(EMAIL_DOMAINS["risky"] + EMAIL_DOMAINS["legit"], weights=[0.8] * 3 + [0.2] * 4)[0]
    return {"isFraud": 0, "transactionAmount": round(np.random.uniform(0.99, 10.99), 2), "currency": "USD", "itemCount": 1, "productCategory": "digital_goods", "isGuestCheckout": 1, "accountAgeInDays": 0, "emailDomain": email_domain, "shippingCountry": country, "billingCountry": country, "billingEqualsShipping": 1, "paymentMethod": random.choice(PAYMENT_METHODS), "ipAddressCountry": country}
def generate_fraud_account_takeover(user_id, account_age, avg_txn_amt_for_user):
    billing_country = random.choice(COUNTRIES); shipping_country = random.choice([c for c in COUNTRIES if c != billing_country]); ip_country = random.choices([billing_country, random.choice([c for c in COUNTRIES if c != billing_country])], weights=[0.3, 0.7], k=1)[0]; fraud_multiplier = np.random.uniform(2, 8); fraud_amount = round(max(200, avg_txn_amt_for_user * fraud_multiplier), 2)
    return {"isFraud": 1, "transactionAmount": fraud_amount, "currency": random.choice(CURRENCIES), "itemCount": np.random.randint(3, 10), "productCategory": "electronics", "isGuestCheckout": 0, "accountAgeInDays": account_age, "emailDomain": random.choice(EMAIL_DOMAINS["legit"]), "shippingCountry": shipping_country, "billingCountry": billing_country, "billingEqualsShipping": 0, "paymentMethod": "credit_card", "ipAddressCountry": ip_country}
def generate_legit_standard(user_id, account_age):
    country = random.choice(COUNTRIES); return {"isFraud": 0, "transactionAmount": round(np.random.uniform(20, 300), 2), "currency": random.choice(CURRENCIES), "itemCount": np.random.randint(1, 5), "productCategory": random.choice(PRODUCT_CATEGORIES), "isGuestCheckout": 0, "accountAgeInDays": account_age, "emailDomain": random.choice(EMAIL_DOMAINS["legit"]), "shippingCountry": country, "billingCountry": country, "billingEqualsShipping": 1, "paymentMethod": random.choice(PAYMENT_METHODS), "ipAddressCountry": country}
def generate_legit_guest_checkout():
    country = random.choice(COUNTRIES); domain_category = random.choices(["legit", "risky"], weights=[0.90, 0.10], k=1)[0]; email_domain = random.choice(EMAIL_DOMAINS[domain_category]); return {"isFraud": 0, "transactionAmount": round(np.random.uniform(15, 150), 2), "currency": random.choice(CURRENCIES), "itemCount": np.random.randint(1, 3), "productCategory": random.choice(PRODUCT_CATEGORIES), "isGuestCheckout": 1, "accountAgeInDays": 0, "emailDomain": email_domain, "shippingCountry": country, "billingCountry": country, "billingEqualsShipping": 1, "paymentMethod": "credit_card", "ipAddressCountry": country}
def generate_legit_high_value(user_id, account_age):
    country = random.choice(COUNTRIES); return {"isFraud": 0, "transactionAmount": round(np.random.uniform(1500, 8000), 2), "currency": random.choice(CURRENCIES), "itemCount": np.random.randint(1, 3), "productCategory": "electronics", "isGuestCheckout": 0, "accountAgeInDays": account_age, "emailDomain": random.choice(EMAIL_DOMAINS["legit"]), "shippingCountry": country, "billingCountry": country, "billingEqualsShipping": 1, "paymentMethod": random.choice(["credit_card", "paypal"]), "ipAddressCountry": country}
def generate_legit_international_gift(user_id, account_age):
    billing_country = random.choice(COUNTRIES); shipping_country = random.choice([c for c in COUNTRIES if c != billing_country]); ip_country = random.choices([billing_country, random.choice([c for c in COUNTRIES if c != billing_country])], weights=[0.90, 0.10], k=1)[0]; return {"isFraud": 0, "transactionAmount": round(np.random.uniform(50, 400), 2), "currency": random.choice(CURRENCIES), "itemCount": np.random.randint(1, 4), "productCategory": random.choice(["books", "clothing", "toys", "beauty"]), "isGuestCheckout": 0, "accountAgeInDays": account_age, "emailDomain": random.choice(EMAIL_DOMAINS["legit"]), "shippingCountry": shipping_country, "billingCountry": billing_country, "billingEqualsShipping": 0, "paymentMethod": random.choice(PAYMENT_METHODS), "ipAddressCountry": ip_country}
def generate_legit_user_on_vacation(user_id, account_age):
    home_country = random.choice(COUNTRIES); vacation_country = random.choice([c for c in COUNTRIES if c != home_country]); return {"isFraud": 0, "transactionAmount": round(np.random.uniform(50, 600), 2), "currency": random.choice(CURRENCIES), "itemCount": np.random.randint(1, 6), "productCategory": random.choice(["clothing", "beauty", "toys", "sports"]), "isGuestCheckout": 0, "accountAgeInDays": account_age, "emailDomain": random.choice(EMAIL_DOMAINS["legit"]), "shippingCountry": home_country, "billingCountry": home_country, "billingEqualsShipping": 1, "paymentMethod": "credit_card", "ipAddressCountry": vacation_country}
def generate_legit_complex_gift(user_id, account_age):
    billing_country = random.choice(COUNTRIES); shipping_country = random.choice([c for c in COUNTRIES if c != billing_country]); ip_country = random.choice([c for c in COUNTRIES if c != billing_country and c != shipping_country]); return {"isFraud": 0, "transactionAmount": round(np.random.uniform(50, 400), 2), "currency": random.choice(CURRENCIES), "itemCount": np.random.randint(1, 4), "productCategory": random.choice(["books", "clothing", "toys", "beauty"]), "isGuestCheckout": 0, "accountAgeInDays": account_age, "emailDomain": random.choice(EMAIL_DOMAINS["legit"]), "shippingCountry": shipping_country, "billingCountry": billing_country, "billingEqualsShipping": 0, "paymentMethod": random.choice(PAYMENT_METHODS), "ipAddressCountry": ip_country}
def generate_legit_spending_spike(user_id, account_age):
    country = random.choice(COUNTRIES); return {"isFraud": 0, "transactionAmount": round(np.random.uniform(1000, 7000), 2), "currency": random.choice(CURRENCIES), "itemCount": np.random.randint(1, 3), "productCategory": random.choice(["electronics", "home_goods"]), "isGuestCheckout": 0, "accountAgeInDays": account_age, "emailDomain": random.choice(EMAIL_DOMAINS["legit"]), "shippingCountry": country, "billingCountry": country, "billingEqualsShipping": 1, "paymentMethod": "credit_card", "ipAddressCountry": country}
def generate_fraud_card_testing(user_id, account_age):
    country = random.choice(COUNTRIES); return {"isFraud": 1, "transactionAmount": round(np.random.uniform(0.50, 4.99), 2), "currency": "USD", "itemCount": 1, "productCategory": "digital_goods", "isGuestCheckout": 1 if account_age == 0 else 0, "accountAgeInDays": account_age, "emailDomain": random.choice(EMAIL_DOMAINS["risky"]), "shippingCountry": country, "billingCountry": country, "billingEqualsShipping": 1, "paymentMethod": "credit_card", "ipAddressCountry": random.choice([c for c in COUNTRIES if c != country])}
def generate_fraud_shipping_reship(user_id, account_age):
    billing_country = random.choice(COUNTRIES); shipping_country = random.choice([c for c in COUNTRIES if c != billing_country]); ip_country = random.choices([billing_country, shipping_country, random.choice([c for c in COUNTRIES if c not in [billing_country, shipping_country]])], weights=[0.2, 0.2, 0.6], k=1)[0]; email_domain = random.choices(EMAIL_DOMAINS["legit"] + EMAIL_DOMAINS["risky"], weights=[0.6] * 4 + [0.4] * 3)[0]; return {"isFraud": 1, "transactionAmount": round(np.random.uniform(200, 1200), 2), "currency": random.choice(CURRENCIES), "itemCount": np.random.randint(1, 3), "productCategory": random.choice(["electronics", "beauty"]), "isGuestCheckout": 1 if account_age < 30 else 0, "accountAgeInDays": account_age, "emailDomain": email_domain, "shippingCountry": shipping_country, "billingCountry": billing_country, "billingEqualsShipping": 0, "paymentMethod": "credit_card", "ipAddressCountry": ip_country}

# --- MAIN SCRIPT ---
if __name__ == "__main__":
    print(f"--- Generating FINAL CHALLENGE Dataset (v8.1) with {CONFIG['NUM_RECORDS']} records ---")
    user_pool = {generate_user_id(): {"age": random.choices([np.random.randint(0, 364), np.random.randint(365, 2000)], weights=[0.7, 0.3], k=1)[0], "avg_spend": np.random.uniform(30, 250)} for _ in range(CONFIG['NUM_RECORDS'] // 10)}
    user_ids = list(user_pool.keys())
    archetype_funcs = {"legit_standard": generate_legit_standard, "legit_guest_checkout": generate_legit_guest_checkout, "legit_high_value": generate_legit_high_value, "legit_international_gift": generate_legit_international_gift, "legit_user_on_vacation": generate_legit_user_on_vacation, "legit_complex_gift": generate_legit_complex_gift, "legit_spending_spike": generate_legit_spending_spike, "legit_risky_micro_guest": generate_legit_risky_micro_guest, "fraud_account_takeover": generate_fraud_account_takeover, "fraud_card_testing": generate_fraud_card_testing, "fraud_shipping_reship": generate_fraud_shipping_reship}
    choices = list(archetype_funcs.keys()); proportions = [CONFIG["ARCHETYPE_PROPORTIONS"][key] for key in choices]
    all_records = []
    for _ in tqdm(range(CONFIG["NUM_RECORDS"]), desc="Generating Records"):
        archetype_choice = np.random.choice(choices, p=proportions)
        if archetype_choice in ["legit_guest_checkout", "legit_risky_micro_guest"]:
            record = archetype_funcs[archetype_choice](); record["userId"] = generate_user_id()
        else:
            if archetype_choice == "legit_spending_spike":
                valid_users = [uid for uid, data in user_pool.items() if data["age"] > 365]; user_id = random.choice(valid_users)
            else: user_id = random.choice(user_ids)
            user_data = user_pool[user_id]
            if archetype_choice == "fraud_account_takeover":
                record = archetype_funcs[archetype_choice](user_id, user_data["age"], user_data["avg_spend"])
            else: record = archetype_funcs[archetype_choice](user_id, user_data["age"])
            record["userId"] = user_id
        record["transactionTimestamp"] = generate_timestamp()
        all_records.append(record)
    df = pd.DataFrame(all_records); df = df.sample(frac=1).reset_index(drop=True)
    final_columns = ['userId', 'transactionTimestamp', 'transactionAmount', 'currency', 'itemCount', 'productCategory', 'isGuestCheckout', 'accountAgeInDays', 'emailDomain', 'shippingCountry', 'billingCountry', 'billingEqualsShipping', 'paymentMethod', 'ipAddressCountry', 'isFraud']
    df[final_columns].to_csv(CONFIG["OUTPUT_FILENAME"], index=False)
    print("\n" + "="*50); print("✅ SUCCESS!"); print(f"New FINAL CHALLENGE dataset saved as: {CONFIG['OUTPUT_FILENAME']}")
    print("This dataset is the definitive version, designed to produce a truly realistic model."); print("="*50)