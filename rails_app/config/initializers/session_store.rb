# Be sure to restart your server when you modify this file.

# Your secret key for verifying cookie session data integrity.
# If you change this key, all old sessions will become invalid!
# Make sure the secret is at least 30 characters and all random, 
# no regular words or you'll be exposed to dictionary attacks.
ActionController::Base.session = {
  :key         => '_pestsupply_session',
  :secret      => '00debe5dfd955bf061f7402e7e8b582720ee62286bd517458c3a9d780a54e6f57afbda6b320ebab7fe2dd89c4717a7156aa5b39d35d3f828d69c35f5e6dd1999'
}

# Use the database for sessions instead of the cookie-based default,
# which shouldn't be used to store highly confidential information
# (create the session table with "rake db:sessions:create")
# ActionController::Base.session_store = :active_record_store
