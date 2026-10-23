/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.service;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A {@link Pageable} defined by an arbitrary {@code offset} and a {@code limit}, as Fineract APIs
 * expose paging. Spring's {@code PageRequest} only supports whole pages. The ordering is part of
 * the repository query, so this one is always unsorted.
 */
final class OffsetPageRequest implements Pageable {

  private final long offset;
  private final int limit;

  OffsetPageRequest(final long offset, final int limit) {
    if (offset < 0 || limit < 1) {
      throw new IllegalArgumentException("offset must be >= 0 and limit >= 1");
    }
    this.offset = offset;
    this.limit = limit;
  }

  @Override
  public int getPageNumber() {
    return (int) (offset / limit);
  }

  @Override
  public int getPageSize() {
    return limit;
  }

  @Override
  public long getOffset() {
    return offset;
  }

  @Override
  public Sort getSort() {
    return Sort.unsorted();
  }

  @Override
  public Pageable next() {
    return new OffsetPageRequest(offset + limit, limit);
  }

  @Override
  public Pageable previousOrFirst() {
    return new OffsetPageRequest(Math.max(0, offset - limit), limit);
  }

  @Override
  public Pageable first() {
    return new OffsetPageRequest(0, limit);
  }

  @Override
  public Pageable withPage(final int pageNumber) {
    return new OffsetPageRequest((long) pageNumber * limit, limit);
  }

  @Override
  public boolean hasPrevious() {
    return offset > 0;
  }
}
