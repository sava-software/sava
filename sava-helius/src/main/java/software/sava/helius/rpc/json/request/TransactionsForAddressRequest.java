package software.sava.helius.rpc.json.request;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.Commitment;

public record TransactionsForAddressRequest(PublicKey address,
                                            SortOrder sortOrder,
                                            Commitment commitment,
                                            Long minContextSlot,
                                            int limit,
                                            String paginationToken,
                                            Integer maxSupportedTransactionVersion,
                                            SlotFilter slotFilter,
                                            BlockTimeFilter blockTimeFilter,
                                            SignatureFilter signatureFilter,
                                            Status status,
                                            TokenAccounts tokenAccounts) {

  public record SlotFilter(Long gte, Long gt, Long lte, Long lt) {
  }

  public record BlockTimeFilter(Long gte, Long gt, Long lte, Long lt, Long eq) {
  }

  public record SignatureFilter(String gte, String gt, String lte, String lt) {
  }

  public static Builder build(final PublicKey address) {
    return new Builder(address);
  }

  public String toJson(final long id, final String transactionDetails) {
    final var json = new StringBuilder(512);
    json.append("{\"jsonrpc\":\"2.0\",\"id\":").append(id);
    json.append(",\"method\":\"getTransactionsForAddress\",\"params\":[\"");
    json.append(address.toBase58());
    json.append("\",{\"encoding\":\"base64\",\"transactionDetails\":\"").append(transactionDetails).append('"');
    if (sortOrder != null) {
      json.append(",\"sortOrder\":\"").append(sortOrder.name()).append('"');
    }
    if (commitment != null) {
      json.append(",\"commitment\":\"").append(commitment.getValue()).append('"');
    }
    if (minContextSlot != null) {
      json.append(",\"minContextSlot\":").append(minContextSlot);
    }
    if (limit > 0) {
      json.append(",\"limit\":").append(limit);
    }
    if (paginationToken != null) {
      json.append(",\"paginationToken\":\"").append(paginationToken).append('"');
    }
    if (maxSupportedTransactionVersion != null) {
      json.append(",\"maxSupportedTransactionVersion\":").append(maxSupportedTransactionVersion);
    }

    final boolean hasFilters = slotFilter != null || blockTimeFilter != null
        || signatureFilter != null || status != null || tokenAccounts != null;
    if (hasFilters) {
      json.append(',');
      json.append("\"filters\":{");
      boolean hasFilterField = false;

      if (slotFilter != null) {
        json.append("\"slot\":{");
        boolean hasSlotField = false;
        if (slotFilter.gte != null) {
          json.append("\"gte\":").append(slotFilter.gte);
          hasSlotField = true;
        }
        if (slotFilter.gt != null) {
          if (hasSlotField) json.append(',');
          json.append("\"gt\":").append(slotFilter.gt);
          hasSlotField = true;
        }
        if (slotFilter.lte != null) {
          if (hasSlotField) json.append(',');
          json.append("\"lte\":").append(slotFilter.lte);
          hasSlotField = true;
        }
        if (slotFilter.lt != null) {
          if (hasSlotField) json.append(',');
          json.append("\"lt\":").append(slotFilter.lt);
        }
        json.append('}');
        hasFilterField = true;
      }

      if (blockTimeFilter != null) {
        if (hasFilterField) json.append(',');
        json.append("\"blockTime\":{");
        boolean hasBlockTimeField = false;
        if (blockTimeFilter.gte != null) {
          json.append("\"gte\":").append(blockTimeFilter.gte);
          hasBlockTimeField = true;
        }
        if (blockTimeFilter.gt != null) {
          if (hasBlockTimeField) json.append(',');
          json.append("\"gt\":").append(blockTimeFilter.gt);
          hasBlockTimeField = true;
        }
        if (blockTimeFilter.lte != null) {
          if (hasBlockTimeField) json.append(',');
          json.append("\"lte\":").append(blockTimeFilter.lte);
          hasBlockTimeField = true;
        }
        if (blockTimeFilter.lt != null) {
          if (hasBlockTimeField) json.append(',');
          json.append("\"lt\":").append(blockTimeFilter.lt);
          hasBlockTimeField = true;
        }
        if (blockTimeFilter.eq != null) {
          if (hasBlockTimeField) json.append(',');
          json.append("\"eq\":").append(blockTimeFilter.eq);
        }
        json.append('}');
        hasFilterField = true;
      }

      if (signatureFilter != null) {
        if (hasFilterField) json.append(',');
        json.append("\"signature\":{");
        boolean hasSigField = false;
        if (signatureFilter.gte != null) {
          json.append("\"gte\":\"").append(signatureFilter.gte).append('"');
          hasSigField = true;
        }
        if (signatureFilter.gt != null) {
          if (hasSigField) json.append(',');
          json.append("\"gt\":\"").append(signatureFilter.gt).append('"');
          hasSigField = true;
        }
        if (signatureFilter.lte != null) {
          if (hasSigField) json.append(',');
          json.append("\"lte\":\"").append(signatureFilter.lte).append('"');
          hasSigField = true;
        }
        if (signatureFilter.lt != null) {
          if (hasSigField) json.append(',');
          json.append("\"lt\":\"").append(signatureFilter.lt).append('"');
        }
        json.append('}');
        hasFilterField = true;
      }

      if (status != null) {
        if (hasFilterField) json.append(',');
        json.append("\"status\":\"").append(status.name()).append('"');
        hasFilterField = true;
      }

      if (tokenAccounts != null) {
        if (hasFilterField) json.append(',');
        json.append("\"tokenAccounts\":\"").append(tokenAccounts.name()).append('"');
      }

      json.append('}');
    }

    json.append("}]}");
    return json.toString();
  }

  public static final class Builder {

    private final PublicKey address;
    private SortOrder sortOrder;
    private Commitment commitment;
    private Long minContextSlot;
    private int limit;
    private String paginationToken;
    private Integer maxSupportedTransactionVersion;
    private SlotFilter slotFilter;
    private BlockTimeFilter blockTimeFilter;
    private SignatureFilter signatureFilter;
    private Status status;
    private TokenAccounts tokenAccounts;

    private Builder(final PublicKey address) {
      this.address = address;
    }

    public Builder sortOrder(final SortOrder sortOrder) {
      this.sortOrder = sortOrder;
      return this;
    }

    public Builder commitment(final Commitment commitment) {
      this.commitment = commitment;
      return this;
    }

    public Builder minContextSlot(final long minContextSlot) {
      this.minContextSlot = minContextSlot;
      return this;
    }

    public Builder limit(final int limit) {
      this.limit = limit;
      return this;
    }

    public Builder paginationToken(final String paginationToken) {
      this.paginationToken = paginationToken;
      return this;
    }

    public Builder maxSupportedTransactionVersion(final int maxSupportedTransactionVersion) {
      this.maxSupportedTransactionVersion = maxSupportedTransactionVersion;
      return this;
    }

    public Builder slotFilter(final Long gte, final Long gt, final Long lte, final Long lt) {
      this.slotFilter = new SlotFilter(gte, gt, lte, lt);
      return this;
    }

    public Builder blockTimeFilter(final Long gte, final Long gt, final Long lte, final Long lt, final Long eq) {
      this.blockTimeFilter = new BlockTimeFilter(gte, gt, lte, lt, eq);
      return this;
    }

    public Builder signatureFilter(final String gte, final String gt, final String lte, final String lt) {
      this.signatureFilter = new SignatureFilter(gte, gt, lte, lt);
      return this;
    }

    public Builder status(final Status status) {
      this.status = status;
      return this;
    }

    public Builder tokenAccounts(final TokenAccounts tokenAccounts) {
      this.tokenAccounts = tokenAccounts;
      return this;
    }

    public TransactionsForAddressRequest createRequest() {
      return new TransactionsForAddressRequest(
          address, sortOrder, commitment,
          minContextSlot, limit, paginationToken,
          maxSupportedTransactionVersion, slotFilter, blockTimeFilter,
          signatureFilter, status, tokenAccounts
      );
    }
  }
}
