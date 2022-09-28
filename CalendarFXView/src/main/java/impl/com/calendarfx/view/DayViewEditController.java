/*
 *  Copyright (C) 2017 Dirk Lemmermann Software & Consulting (dlsc.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package impl.com.calendarfx.view;

import com.calendarfx.model.Calendar;
import com.calendarfx.model.Entry;
import com.calendarfx.util.LoggingDomain;
import com.calendarfx.view.DateControl.EditOperation;
import com.calendarfx.view.DateControl.EntryEditParameter;
import com.calendarfx.view.DayEntryView;
import com.calendarfx.view.DayView;
import com.calendarfx.view.DayViewBase;
import com.calendarfx.view.DraggedEntry;
import com.calendarfx.view.DraggedEntry.DragMode;
import com.calendarfx.view.EntryViewBase;
import com.calendarfx.view.EntryViewBase.HeightLayoutStrategy;
import com.calendarfx.view.RequestEvent;
import com.calendarfx.view.VirtualGrid;
import com.calendarfx.view.WeekView;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.event.EventHandler;
import javafx.scene.Cursor;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.util.Callback;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

public class DayViewEditController {

    private static final Logger LOGGER = LoggingDomain.EDITING;

    private boolean dragging;
    private boolean creating;
    private final DayViewBase dayViewBase;
    private DayEntryView dayEntryView;
    private Entry<?> entry;
    private DraggedEntry.DragMode dragMode;
    private Handle handle;
    private Duration offsetDuration;
    private Duration entryDuration;

    public DayViewEditController(DayViewBase dayView) {
        this.dayViewBase = Objects.requireNonNull(dayView);

        dayView.addEventFilter(MouseEvent.MOUSE_PRESSED, this::mousePressed);
        dayView.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::mouseDragged);

        final EventHandler<MouseEvent> mouseReleasedHandler = this::mouseReleased;

        // mouse released is very important for us. register with the scene, so we get that in any case.
        if (dayView.getScene() != null) {
            dayView.addEventFilter(MouseEvent.MOUSE_RELEASED, mouseReleasedHandler);
        }

        // also register with the scene property. Mostly to remove our event filter if the component gets destroyed.
        dayView.sceneProperty().addListener(((observable, oldValue, newValue) -> {
            if (oldValue != null) {
                oldValue.removeEventFilter(MouseEvent.MOUSE_RELEASED, mouseReleasedHandler);
            }
            if (newValue != null) {
                newValue.addEventFilter(MouseEvent.MOUSE_RELEASED, mouseReleasedHandler);
            }
        }));
        dayView.addEventFilter(MouseEvent.MOUSE_MOVED, this::mouseMoved);

        lassoStartProperty().addListener(it -> dayView.setLassoStart(getLassoStart()));
        lassoEndProperty().addListener(it -> dayView.setLassoEnd(getLassoEnd()));

        setOnLassoFinished(dayView.getOnLassoFinished());
        dayView.onLassoFinishedProperty().addListener(it -> setOnLassoFinished(dayView.getOnLassoFinished()));
    }

    private enum Handle {
        TOP,
        CENTER,
        BOTTOM
    }

    private void initDragModeAndHandle(MouseEvent evt) {
        dragMode = null;
        handle = null;

        if (!(evt.getTarget() instanceof EntryViewBase)) {
            return;
        }

        dayEntryView = (DayEntryView) evt.getTarget();

        entry = dayEntryView.getEntry();
        Calendar calendar = entry.getCalendar();
        if (calendar.isReadOnly()) {
            return;
        }

        double y = evt.getY() - dayEntryView.getBoundsInParent().getMinY();

        LOGGER.finer("y-coordinate inside entry view: " + y);

        if (y > dayEntryView.getHeight() - 5) {
            if (dayEntryView.getHeightLayoutStrategy().equals(HeightLayoutStrategy.USE_START_AND_END_TIME) && dayViewBase.getEntryEditPolicy().call(new EntryEditParameter(dayViewBase, entry, EditOperation.CHANGE_END))) {
                dragMode = DraggedEntry.DragMode.END_TIME;
                handle = Handle.BOTTOM;
            }
        } else if (y < 5) {
            if (dayEntryView.getHeightLayoutStrategy().equals(HeightLayoutStrategy.USE_START_AND_END_TIME) && dayViewBase.getEntryEditPolicy().call(new EntryEditParameter(dayViewBase, entry, EditOperation.CHANGE_START))) {
                dragMode = DraggedEntry.DragMode.START_TIME;
                handle = Handle.TOP;
            }
        } else {
            if (dayViewBase.getEntryEditPolicy().call(new EntryEditParameter(dayViewBase, entry, EditOperation.MOVE))) {
                dragMode = DraggedEntry.DragMode.START_AND_END_TIME;
                handle = Handle.CENTER;
            }
        }
    }

    private void mouseMoved(MouseEvent evt) {
        if (!dragging) {
            initDragModeAndHandle(evt);
        }

        if (dayEntryView != null) {
            if (handle == null) {
                dayEntryView.setCursor(Cursor.DEFAULT);
                return;
            }

            switch (handle) {
                case TOP:
                    dayEntryView.setCursor(Cursor.N_RESIZE);
                    break;
                case BOTTOM:
                    dayEntryView.setCursor(Cursor.S_RESIZE);
                    break;
                case CENTER:
                    dayEntryView.setCursor(Cursor.MOVE);
                    break;
                default:
                    dayEntryView.setCursor(Cursor.DEFAULT);
                    break;
            }
        }
    }

    private boolean showEntryDetails = false;

    private void mousePressed(MouseEvent evt) {
        showEntryDetails = false;

        System.out.println("target: " + evt.getTarget());

        if (evt.isConsumed() || !evt.getButton().equals(MouseButton.PRIMARY) || evt.getClickCount() > 1) {
            return;
        }

        if (!dayViewBase.isScrollingEnabled()) {
            VirtualGrid availabilityGrid = dayViewBase.getAvailabilityGrid();
            setLassoStart(snapToGrid(dayViewBase.getInstantAt(evt), availabilityGrid, false));
            setLassoEnd(snapToGrid(dayViewBase.getInstantAt(evt), availabilityGrid, false).plus(availabilityGrid.getAmount(), availabilityGrid.getUnit()));
        }

        if (dayViewBase.isEditAvailability()) {
            return;
        }

        dragMode = null;
        handle = null;

        LOGGER.finer("mouse event source: " + evt.getSource());
        LOGGER.finer("mouse event target: " + evt.getTarget());
        LOGGER.finer("mouse event y-coordinate:" + evt.getY());
        LOGGER.finer("time: " + dayViewBase.getZonedDateTimeAt(evt.getX(), evt.getY(), dayViewBase.getZoneId()));

        boolean initiallyHideDraggedEntry = false;
        creating = false;

        if (!dayViewBase.isScrollingEnabled() && evt.getTarget() instanceof DayView) {
            creating = true;

            Optional<Calendar> calendar = dayViewBase.getCalendarAt(evt.getX(), evt.getY());
            Instant instantAt = dayViewBase.getInstantAt(evt);

            VirtualGrid virtualGrid = dayViewBase.getVirtualGrid();
            if (virtualGrid != null) {
                instantAt = snapToGrid(instantAt, virtualGrid, false);
            }

            ZonedDateTime time = ZonedDateTime.ofInstant(instantAt, dayViewBase.getZoneId());
            entry = dayViewBase.createEntryAt(time, calendar.orElse(null), true);

            if (virtualGrid != null) {
                entry.setInterval(entry.getInterval().withEndTime(entry.getInterval().getStartTime().plus(virtualGrid.getAmount(), virtualGrid.getUnit())));
            } else {
                entry.setInterval(entry.getInterval().withEndTime(entry.getInterval().getStartTime().plus(entry.getMinimumDuration())));
            }

            DayView dayView = null;

            if (dayViewBase instanceof DayView) {
                dayView = (DayView) dayViewBase;
            } else if (dayViewBase instanceof WeekView) {
                WeekView weekView = (WeekView) dayViewBase;
                dayView = weekView.getWeekDayViews().get(0);
            }

            if (dayView != null) {
                dragMode = DragMode.END_TIME;
                handle = Handle.BOTTOM;
                dragging = true;
                showEntryDetails = true;
            }

            initiallyHideDraggedEntry = true;

        } else if (evt.getTarget() instanceof EntryViewBase) {
            initDragModeAndHandle(evt);
        } else {
            return;
        }

        LOGGER.finer("drag mode: " + dragMode);
        LOGGER.finer("handle: " + handle);

        Callback<EntryEditParameter, Boolean> entryEditPolicy = dayViewBase.getEntryEditPolicy();

        switch (dragMode) {
            case START_AND_END_TIME:
                if (entryEditPolicy.call(new EntryEditParameter(dayViewBase, entry, EditOperation.MOVE))) {
                    dragging = true;
                    if (dayEntryView != null) {
                        dayEntryView.getProperties().put("dragged", true);
                    }

                    Instant time = dayViewBase.getInstantAt(evt);
                    offsetDuration = Duration.between(entry.getStartAsZonedDateTime().toInstant(), time);
                    entryDuration = entry.getDuration();

                    LOGGER.finer("time at mouse pressed location: " + time);
                    LOGGER.finer("offset duration: " + offsetDuration);
                    LOGGER.finer("entry duration: " + entryDuration);

                    dayViewBase.requestLayout();
                }
                break;
            case END_TIME:
                if (entryEditPolicy.call(new EntryEditParameter(dayViewBase, entry, EditOperation.CHANGE_END))) {
                    dragging = true;
                    if (dayEntryView != null) {
                        dayEntryView.getProperties().put("dragged-end", true);
                    }
                }
                break;
            case START_TIME:
                if (entryEditPolicy.call(new EntryEditParameter(dayViewBase, entry, EditOperation.CHANGE_START))) {
                    dragging = true;
                    if (dayEntryView != null) {
                        dayEntryView.getProperties().put("dragged-start", true);
                    }
                }
                break;
            default:
                break;
        }

        if (!dragging) {
            return;
        }

        if (dayViewBase != null) {
            DraggedEntry draggedEntry = new DraggedEntry(entry, dragMode);
            draggedEntry.setOffsetDuration(offsetDuration);
            draggedEntry.setHidden(initiallyHideDraggedEntry);
            dayViewBase.setDraggedEntry(draggedEntry);
        }
    }


    private void mouseReleased(MouseEvent evt) {
        if (evt.getClickCount() > 1) {
            return;
        }

        if (!dayViewBase.isScrollingEnabled()) {
            getOnLassoFinished().accept(getLassoStart(), getLassoEnd());

            setLassoStart(null);
            setLassoEnd(null);
        }

        if (!evt.getButton().equals(MouseButton.PRIMARY) || dragMode == null || !dragging) {
            return;
        }

        dragging = false;

        Calendar calendar = entry.getCalendar();
        if (calendar.isReadOnly()) {
            return;
        }

        if (dayEntryView != null) {
            dayEntryView.getProperties().put("dragged", false);
            dayEntryView.getProperties().put("dragged-start", false);
            dayEntryView.getProperties().put("dragged-end", false);
        }

        /*
         * We might run in the sampler application. Then the entry view will not
         * be inside a date control.
         */
        DraggedEntry draggedEntry = dayViewBase.getDraggedEntry();

        if (draggedEntry != null) {
            dayViewBase.setDraggedEntry(null);
            if (!draggedEntry.isHidden()) {
                entry.setInterval(draggedEntry.getInterval());
                if (dayViewBase.isShowDetailsUponEntryCreation() && showEntryDetails) {
                    dayViewBase.fireEvent(new RequestEvent(dayViewBase, dayViewBase, entry));
                }
            } else {
                entry.removeFromCalendar();
            }
        }
    }

    private void mouseDragged(MouseEvent evt) {
        if (evt.isStillSincePress()) {
            return;
        }

        if (!dayViewBase.isScrollingEnabled()) {
            setLassoEnd(snapToGrid(dayViewBase.getInstantAt(evt), dayViewBase.getAvailabilityGrid(), true));
        }

        if (dayViewBase.isEditAvailability() || !evt.getButton().equals(MouseButton.PRIMARY) || dragMode == null || !dragging) {
            return;
        }

        Calendar calendar = entry.getCalendar();
        if (calendar.isReadOnly()) {
            return;
        }

        /*
         * We might run in the sampler application. Then the entry view will not
         * be inside a date control.
         */
        DraggedEntry draggedEntry = dayViewBase.getDraggedEntry();

        if (draggedEntry != null) {
            draggedEntry.getOriginalEntry().setHidden(false);
            draggedEntry.setHidden(false);
        }

        switch (dragMode) {
            case START_TIME:
                switch (handle) {
                    case TOP:
                    case BOTTOM:
                        changeStartTime(evt);
                        break;
                    case CENTER:
                        break;
                }
                break;
            case END_TIME:
                switch (handle) {
                    case TOP:
                    case BOTTOM:
                        changeEndTime(evt);
                        break;
                    case CENTER:
                        break;
                }
                break;
            case START_AND_END_TIME:
                changeStartAndEndTime(evt);
                break;
        }
    }

    private void changeStartTime(MouseEvent evt) {
        DraggedEntry draggedEntry = dayViewBase.getDraggedEntry();

        Instant gridTime = fixTimeIfOutsideView(evt, snapToGrid(dayViewBase.getInstantAt(evt), dayViewBase.getVirtualGrid(), true));

        LOGGER.finer("changing start time, time = " + gridTime);

        if (isMinimumDuration(entry, entry.getEndAsZonedDateTime().toInstant(), gridTime)) {

            LocalDate startDate;
            LocalDate endDate;

            LocalTime startTime;
            LocalTime endTime;

            ZonedDateTime gridZonedTime = ZonedDateTime.ofInstant(gridTime, draggedEntry.getZoneId());

            if (gridTime.isAfter(entry.getEndAsZonedDateTime().toInstant())) {
                if (dayViewBase.isEnableStartAndEndTimesFlip()) {
                    startTime = entry.getEndTime();
                    startDate = entry.getEndDate();
                    endTime = gridZonedTime.toLocalTime();
                    endDate = gridZonedTime.toLocalDate();
                } else {
                    startTime = entry.getEndTime().minus(entry.getMinimumDuration());
                    startDate = entry.getEndDate();
                    endTime = entry.getEndTime();
                    endDate = entry.getEndDate();
                }
            } else {
                startDate = gridZonedTime.toLocalDate();
                startTime = gridZonedTime.toLocalTime();
                endTime = entry.getEndTime();
                endDate = entry.getEndDate();
            }

            LOGGER.finer("new interval: sd = " + startDate + ", st = " + startTime + ", ed = " + endDate + ", et = " + endTime);

            draggedEntry.setInterval(startDate, startTime, endDate, endTime);
        }
    }

    private void changeEndTime(MouseEvent evt) {
        DraggedEntry draggedEntry = dayViewBase.getDraggedEntry();

        Instant gridTime = fixTimeIfOutsideView(evt, snapToGrid(dayViewBase.getInstantAt(evt), dayViewBase.getVirtualGrid(), true));

        LOGGER.finer("changing end time, time = " + gridTime);

        if (isMinimumDuration(entry, entry.getStartAsZonedDateTime().toInstant(), gridTime)) {

            LOGGER.finer("dragged entry: " + draggedEntry.getInterval());

            LocalDate startDate;
            LocalDate endDate;

            LocalTime startTime;
            LocalTime endTime;

            ZonedDateTime gridZonedTime = ZonedDateTime.ofInstant(gridTime, draggedEntry.getZoneId());

            if (gridTime.isBefore(entry.getStartAsZonedDateTime().toInstant())) {
                if (dayViewBase.isEnableStartAndEndTimesFlip()) {
                    endTime = entry.getStartTime();
                    endDate = entry.getStartDate();
                    startTime = gridZonedTime.toLocalTime();
                    startDate = gridZonedTime.toLocalDate();
                } else {
                    startTime = entry.getStartTime();
                    startDate = entry.getStartDate();
                    endTime = entry.getStartTime().plus(entry.getMinimumDuration());
                    endDate = entry.getStartDate();
                }
            } else {
                startTime = entry.getStartTime();
                startDate = entry.getStartDate();
                endTime = gridZonedTime.toLocalTime();
                endDate = gridZonedTime.toLocalDate();
            }

            LOGGER.finer("new interval: sd = " + startDate + ", st = " + startTime + ", ed = " + endDate + ", et = " + endTime);

            draggedEntry.setInterval(startDate, startTime, endDate, endTime);
        }
    }

    private void changeStartAndEndTime(MouseEvent evt) {
        DraggedEntry draggedEntry = dayViewBase.getDraggedEntry();

        Instant locationTime = fixTimeIfOutsideView(evt, dayViewBase.getInstantAt(evt));

        LOGGER.fine("changing start/end time, time = " + locationTime + " offset duration = " + offsetDuration);

        if (locationTime != null && offsetDuration != null) {

            Instant newStartTime = locationTime.minus(offsetDuration);
            LOGGER.fine("new start time = " + newStartTime);

            newStartTime = snapToGrid(newStartTime, dayViewBase.getVirtualGrid(), true);
            Instant newEndTime = newStartTime.plus(entryDuration);

            LOGGER.fine("new start time (grid) = " + newStartTime);
            LOGGER.fine("new end time = " + newEndTime);

            ZonedDateTime gridStartZonedTime = ZonedDateTime.ofInstant(newStartTime, draggedEntry.getZoneId());
            ZonedDateTime gridEndZonedTime = ZonedDateTime.ofInstant(newEndTime, draggedEntry.getZoneId());

            LocalDate startDate = gridStartZonedTime.toLocalDate();
            LocalTime startTime = gridStartZonedTime.toLocalTime();

            LocalDate endDate = LocalDateTime.of(startDate, startTime).plus(entryDuration).toLocalDate();
            LocalTime endTime = gridEndZonedTime.toLocalTime();

            draggedEntry.setInterval(startDate, startTime, endDate, endTime);
        }
    }

    private Instant fixTimeIfOutsideView(MouseEvent evt, Instant gridTime) {
        /*
         * Fix the time calculation if the mouse cursor exits the day view area.
         * Note: day view can also be a WeekView as it extends DayViewBase.
         */
        if (evt.getX() > dayViewBase.getWidth() || evt.getX() < 0) {
            ZonedDateTime zdt = ZonedDateTime.ofInstant(gridTime, entry.getZoneId());
            gridTime = ZonedDateTime.of(entry.getStartDate(), zdt.toLocalTime(), zdt.getZone()).toInstant();
        }
        return gridTime;
    }

    private boolean isMinimumDuration(Entry<?> entry, Instant timeA, Instant timeB) {
        Duration minDuration = entry.getMinimumDuration().abs();
        if (minDuration != null) {
            Duration duration = Duration.between(timeA, timeB).abs();
            return !duration.minus(minDuration).isNegative();
        }

        return true;
    }

    private Instant snapToGrid(Instant time, VirtualGrid grid, boolean checkCloser) {
        if (grid == null) {
            return time;
        }

        DayOfWeek firstDayOfWeek = dayViewBase.getFirstDayOfWeek();
        Instant lowerTime = grid.adjustTime(time, dayViewBase.getZoneId(), false, firstDayOfWeek);

        if (checkCloser) {
            Instant upperTime = grid.adjustTime(time, dayViewBase.getZoneId(), true, firstDayOfWeek);
            if (Duration.between(time, upperTime).abs().minus(Duration.between(time, lowerTime).abs()).isNegative()) {
                return upperTime;
            }
        }

        return lowerTime;
    }

    private final ObjectProperty<Instant> lassoStart = new SimpleObjectProperty<>(this, "lassoStart");

    public final Instant getLassoStart() {
        return lassoStart.get();
    }

    public final ObjectProperty<Instant> lassoStartProperty() {
        return lassoStart;
    }

    public final void setLassoStart(Instant lassoStart) {
        this.lassoStart.set(lassoStart);
    }

    private final ObjectProperty<Instant> lassoEnd = new SimpleObjectProperty<>(this, "lassoEnd");

    public final Instant getLassoEnd() {
        return lassoEnd.get();
    }

    public final ObjectProperty<Instant> lassoEndProperty() {
        return lassoEnd;
    }

    public final void setLassoEnd(Instant lassoEnd) {
        this.lassoEnd.set(lassoEnd);
    }

    private final ObjectProperty<BiConsumer<Instant, Instant>> onLassoFinished = new SimpleObjectProperty<>(this, "onLassoFinished", (start, end) -> {
    });

    public final BiConsumer<Instant, Instant> getOnLassoFinished() {
        return onLassoFinished.get();
    }

    public final ObjectProperty<BiConsumer<Instant, Instant>> onLassoFinishedProperty() {
        return onLassoFinished;
    }

    public final void setOnLassoFinished(BiConsumer<Instant, Instant> onLassoFinished) {
        this.onLassoFinished.set(onLassoFinished);
    }
}
