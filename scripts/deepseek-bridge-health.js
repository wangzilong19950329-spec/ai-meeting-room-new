'use strict';

function bridgeReadiness(configuredAccounts, brokerState, sharedTargetStates, startupPreparationComplete) {
  const enabledAccounts = configuredAccounts.filter(account => account.enabled);
  const configuredCapabilities = Array.from(new Set(enabledAccounts.flatMap(account => (
    account.capabilities.includes('*') ? [] : account.capabilities
  )))).sort();
  const targetUsable = accountKey => {
    const target = sharedTargetStates.get(accountKey);
    return target && target.status === 'ready';
  };
  const accountUsable = account => (
    account.enabled
    && account.routingEligible
    && account.identityVerified
    && !account.circuitOpen
    && targetUsable(account.accountKey)
  );
  const availableCapabilities = Array.from(new Set(brokerState.accounts.flatMap(account => (
    accountUsable(account)
      ? account.capabilities.filter(capability => capability !== '*')
      : []
  )))).sort();
  const unavailableCapabilities = configuredCapabilities.filter(
    capability => !availableCapabilities.includes(capability)
  );
  const eligibleIdentityCount = brokerState.accounts.filter(account => (
    account.enabled && account.routingEligible && account.identityVerified
  )).length;
  const usableAccountCount = brokerState.accounts.filter(accountUsable).length;
  const capabilityReady = startupPreparationComplete && unavailableCapabilities.length === 0;
  const capacityReady = startupPreparationComplete && usableAccountCount === enabledAccounts.length;
  return {
    ready: capabilityReady && capacityReady,
    capabilityReady,
    capacityReady,
    configuredAccountCount: enabledAccounts.length,
    eligibleIdentityCount,
    usableAccountCount,
    availableCapabilities,
    unavailableCapabilities
  };
}

module.exports = { bridgeReadiness };
