package com.haloticket.ticket_service.service;

import com.haloticket.ticket_service.client.EventServiceClient;
import com.haloticket.ticket_service.config.CheckinWindowProperties;
import com.haloticket.ticket_service.dto.request.CheckinRequest;
import com.haloticket.ticket_service.dto.response.TicketCheckinDTO;
import com.haloticket.ticket_service.entity.Checkin;
import com.haloticket.ticket_service.entity.Ticket;
import com.haloticket.ticket_service.entity.CheckInAttempt;
import com.haloticket.ticket_service.enums.CheckInAttemptResult;
import com.haloticket.ticket_service.enums.CheckInStatus;
import com.haloticket.ticket_service.enums.TicketStatus;
import com.haloticket.ticket_service.exception.CheckinException;
import com.haloticket.ticket_service.repository.CheckInAttemptRepository;
import com.haloticket.ticket_service.repository.CheckinRepository;
import com.haloticket.ticket_service.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CheckinHardeningServiceTest {

    private CheckinRepository checkinRepository;
    private TicketRepository ticketRepository;
    private CheckInAttemptRepository checkInAttemptRepository;
    private EventScheduleService eventScheduleService;
    private CheckinWindowProperties windowProperties;
    private CheckinService checkinService;

    @BeforeEach
    void setUp() {
        checkinRepository = mock(CheckinRepository.class);
        ticketRepository = mock(TicketRepository.class);
        checkInAttemptRepository = mock(CheckInAttemptRepository.class);
        eventScheduleService = mock(EventScheduleService.class);
        windowProperties = new CheckinWindowProperties();
        windowProperties.setEnabled(false);
        windowProperties.setOpenBeforeMinutes(180);
        windowProperties.setCloseAfterEndMinutes(120);
        windowProperties.setFailOpen(true);

        checkinService = new CheckinService(
                checkinRepository,
                ticketRepository,
                checkInAttemptRepository,
                eventScheduleService,
                windowProperties
        );
    }

    @Test
    void ticketNotFound_recordsAttemptWithNullTicket_throwsCheckinException() {
        CheckinRequest request = CheckinRequest.builder()
                .qrCodeHash("hash-missing")
                .eventId("event-1")
                .staffAssignmentId("staff-1")
                .deviceInfo("device-1")
                .build();
        when(ticketRepository.findByQrCodeHash("hash-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> checkinService.processCheckin(request))
                .isInstanceOf(CheckinException.class)
                .satisfies(ex -> assertThat(((CheckinException) ex).getCode()).isEqualTo("TICKET_NOT_FOUND"));

        ArgumentCaptor<CheckInAttempt> captor = ArgumentCaptor.forClass(CheckInAttempt.class);
        verify(checkInAttemptRepository).save(captor.capture());
        assertThat(captor.getValue().getTicket()).isNull();
        assertThat(captor.getValue().getResult()).isEqualTo(CheckInAttemptResult.FAILED_SIGNATURE);
        assertThat(captor.getValue().getFailureReason()).contains("Ticket not found");
    }

    @Test
    void wrongEvent_recordsAttempt_throwsCheckinException() {
        Ticket ticket = Ticket.builder()
                .id("t1")
                .eventId("event-A")
                .qrCodeHash("hash-1")
                .status(TicketStatus.VALID)
                .build();
        CheckinRequest request = CheckinRequest.builder()
                .qrCodeHash("hash-1")
                .eventId("event-B")
                .build();
        when(ticketRepository.findByQrCodeHash("hash-1")).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> checkinService.processCheckin(request))
                .isInstanceOf(CheckinException.class)
                .satisfies(ex -> assertThat(((CheckinException) ex).getCode()).isEqualTo("WRONG_EVENT"));

        verify(checkInAttemptRepository).save(argThat(a -> a.getResult() == CheckInAttemptResult.FAILED_SIGNATURE));
    }

    @Test
    void wrongShowtime_recordsAttempt_throwsCheckinException() {
        Ticket ticket = Ticket.builder()
                .id("t1")
                .eventId("event-A")
                .showtimeId("st-1")
                .qrCodeHash("hash-1")
                .status(TicketStatus.VALID)
                .build();
        CheckinRequest request = CheckinRequest.builder()
                .qrCodeHash("hash-1")
                .eventId("event-A")
                .showtimeId("st-2")
                .build();
        when(ticketRepository.findByQrCodeHash("hash-1")).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> checkinService.processCheckin(request))
                .isInstanceOf(CheckinException.class)
                .satisfies(ex -> assertThat(((CheckinException) ex).getCode()).isEqualTo("WRONG_SHOWTIME"));

        verify(checkInAttemptRepository).save(argThat(a -> a.getResult() == CheckInAttemptResult.FAILED_SIGNATURE));
    }

    @Test
    void outsideWindow_recordsAttempt_throwsCheckinException() {
        windowProperties.setEnabled(true);
        Ticket ticket = Ticket.builder()
                .id("t1")
                .eventId("event-A")
                .qrCodeHash("hash-1")
                .status(TicketStatus.VALID)
                .build();
        CheckinRequest request = CheckinRequest.builder()
                .qrCodeHash("hash-1")
                .eventId("event-A")
                .build();
        when(ticketRepository.findByQrCodeHash("hash-1")).thenReturn(Optional.of(ticket));

        // Event starts tomorrow -> current time is before windowOpen
        LocalDateTime start = LocalDateTime.now().plusDays(1);
        LocalDateTime end = start.plusHours(2);
        EventServiceClient.EventDetailsResponse schedule = new EventServiceClient.EventDetailsResponse(
                "event-A", start, end, Collections.emptyList());
        when(eventScheduleService.getEventSchedule("event-A")).thenReturn(schedule);

        assertThatThrownBy(() -> checkinService.processCheckin(request))
                .isInstanceOf(CheckinException.class)
                .satisfies(ex -> assertThat(((CheckinException) ex).getCode()).isEqualTo("OUTSIDE_CHECKIN_WINDOW"));

        verify(checkInAttemptRepository).save(argThat(a -> a.getResult() == CheckInAttemptResult.OUTSIDE_CHECKIN_WINDOW));
    }

    @Test
    void windowVerification_failOpenTrue_whenFeignFails_proceeds() {
        windowProperties.setEnabled(true);
        windowProperties.setFailOpen(true);
        Ticket ticket = Ticket.builder()
                .id("t1")
                .eventId("event-A")
                .qrCodeHash("hash-1")
                .status(TicketStatus.VALID)
                .build();
        CheckinRequest request = CheckinRequest.builder()
                .qrCodeHash("hash-1")
                .eventId("event-A")
                .build();
        when(ticketRepository.findByQrCodeHash("hash-1")).thenReturn(Optional.of(ticket));
        when(eventScheduleService.getEventSchedule("event-A")).thenThrow(new RuntimeException("Service Unavailable"));
        when(ticketRepository.markTicketUsedIfValid(eq("t1"), any())).thenReturn(1);

        TicketCheckinDTO result = checkinService.processCheckin(request);
        assertThat(result.getCode()).isEqualTo("SUCCESS");
        assertThat(result.getStatus()).isEqualTo("VALID");
    }

    @Test
    void windowVerification_failOpenFalse_whenFeignFails_throwsCheckinException() {
        windowProperties.setEnabled(true);
        windowProperties.setFailOpen(false);
        Ticket ticket = Ticket.builder()
                .id("t1")
                .eventId("event-A")
                .qrCodeHash("hash-1")
                .status(TicketStatus.VALID)
                .build();
        CheckinRequest request = CheckinRequest.builder()
                .qrCodeHash("hash-1")
                .eventId("event-A")
                .build();
        when(ticketRepository.findByQrCodeHash("hash-1")).thenReturn(Optional.of(ticket));
        when(eventScheduleService.getEventSchedule("event-A")).thenThrow(new RuntimeException("Service Unavailable"));

        assertThatThrownBy(() -> checkinService.processCheckin(request))
                .isInstanceOf(CheckinException.class)
                .satisfies(ex -> assertThat(((CheckinException) ex).getCode()).isEqualTo("OUTSIDE_CHECKIN_WINDOW"));
    }

    @Test
    void ticketLocked_recordsAttempt_doesNotInsertCheckinsRow_returnsInvalid() {
        // Point (e) verification: Scanning locked ticket should NEVER insert into checkins table
        Ticket ticket = Ticket.builder()
                .id("t-locked")
                .eventId("event-A")
                .qrCodeHash("hash-locked")
                .status(TicketStatus.LOCKED_FOR_RESALE)
                .build();
        CheckinRequest request = CheckinRequest.builder()
                .qrCodeHash("hash-locked")
                .eventId("event-A")
                .build();
        when(ticketRepository.findByQrCodeHash("hash-locked")).thenReturn(Optional.of(ticket));

        TicketCheckinDTO result = checkinService.processCheckin(request);

        assertThat(result.getStatus()).isEqualTo(CheckInStatus.INVALID.name());
        assertThat(result.getCode()).isEqualTo("TICKET_LOCKED");

        // Verify checkins row is NEVER saved
        verify(checkinRepository, never()).save(any());
        // Verify attempt is recorded
        verify(checkInAttemptRepository).save(argThat(a -> a.getResult() == CheckInAttemptResult.FAILED_SIGNATURE));
    }

    @Test
    void ticketAlreadyUsed_returnsDuplicatedWithAlreadyUsedCode() {
        Ticket ticket = Ticket.builder()
                .id("t-used")
                .eventId("event-A")
                .qrCodeHash("hash-used")
                .status(TicketStatus.USED)
                .usedAt(LocalDateTime.now().minusHours(1))
                .build();
        Checkin existingCheckin = Checkin.builder()
                .id("checkin-1")
                .ticket(ticket)
                .eventId("event-A")
                .status(CheckInStatus.VALID)
                .checkedInAt(ticket.getUsedAt())
                .build();
        CheckinRequest request = CheckinRequest.builder()
                .qrCodeHash("hash-used")
                .eventId("event-A")
                .build();
        when(ticketRepository.findByQrCodeHash("hash-used")).thenReturn(Optional.of(ticket));
        when(checkinRepository.findByTicketId("t-used")).thenReturn(Optional.of(existingCheckin));

        TicketCheckinDTO result = checkinService.processCheckin(request);

        assertThat(result.getStatus()).isEqualTo(CheckInStatus.DUPLICATED.name());
        assertThat(result.getCode()).isEqualTo("ALREADY_USED");
        verify(checkinRepository, never()).save(any());
        verify(checkInAttemptRepository).save(argThat(a -> a.getResult() == CheckInAttemptResult.FAILED_ALREADY_IN));
    }

    @Test
    void concurrentScan_firstWins_secondGetsZeroUpdatedRowsAndReturnsDuplicated() {
        Ticket ticket = Ticket.builder()
                .id("t-race")
                .eventId("event-A")
                .qrCodeHash("hash-race")
                .status(TicketStatus.VALID)
                .build();
        CheckinRequest request = CheckinRequest.builder()
                .qrCodeHash("hash-race")
                .eventId("event-A")
                .build();
        when(ticketRepository.findByQrCodeHash("hash-race")).thenReturn(Optional.of(ticket));

        // Thread 1: markTicketUsedIfValid returns 1
        when(ticketRepository.markTicketUsedIfValid(eq("t-race"), any())).thenReturn(1);
        TicketCheckinDTO winResult = checkinService.processCheckin(request);
        assertThat(winResult.getStatus()).isEqualTo(CheckInStatus.VALID.name());
        assertThat(winResult.getCode()).isEqualTo("SUCCESS");
        verify(checkinRepository, times(1)).save(any(Checkin.class));

        // Thread 2: markTicketUsedIfValid returns 0 (concurrently won by Thread 1)
        when(ticketRepository.markTicketUsedIfValid(eq("t-race"), any())).thenReturn(0);
        Ticket refreshedTicket = Ticket.builder()
                .id("t-race")
                .eventId("event-A")
                .qrCodeHash("hash-race")
                .status(TicketStatus.USED)
                .usedAt(LocalDateTime.now())
                .build();
        when(ticketRepository.findById("t-race")).thenReturn(Optional.of(refreshedTicket));

        TicketCheckinDTO loseResult = checkinService.processCheckin(request);
        assertThat(loseResult.getStatus()).isEqualTo(CheckInStatus.DUPLICATED.name());
        assertThat(loseResult.getCode()).isEqualTo("ALREADY_USED");
        // Checkin save was not called again
        verify(checkinRepository, times(1)).save(any(Checkin.class));
    }

    @Test
    void concurrentCheckin_50Threads_exactlyOneWins_othersGetDuplicated() throws Exception {
        Ticket ticket = Ticket.builder()
                .id("t-multi")
                .eventId("event-A")
                .qrCodeHash("hash-multi")
                .status(TicketStatus.VALID)
                .build();
        CheckinRequest request = CheckinRequest.builder()
                .qrCodeHash("hash-multi")
                .eventId("event-A")
                .build();
        when(ticketRepository.findByQrCodeHash("hash-multi")).thenReturn(Optional.of(ticket));

        java.util.concurrent.atomic.AtomicInteger winnerCount = new java.util.concurrent.atomic.AtomicInteger(0);
        when(ticketRepository.markTicketUsedIfValid(eq("t-multi"), any())).thenAnswer(inv -> {
            if (winnerCount.compareAndSet(0, 1)) {
                return 1;
            }
            return 0;
        });

        Ticket usedTicket = Ticket.builder()
                .id("t-multi")
                .eventId("event-A")
                .qrCodeHash("hash-multi")
                .status(TicketStatus.USED)
                .usedAt(LocalDateTime.now())
                .build();
        when(ticketRepository.findById("t-multi")).thenReturn(Optional.of(usedTicket));

        int threadCount = 50;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(threadCount);

        java.util.List<TicketCheckinDTO> results = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    TicketCheckinDTO dto = checkinService.processCheckin(request);
                    results.add(dto);
                } catch (Exception e) {
                    // unexpected
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(results).hasSize(50);
        long validCount = results.stream().filter(r -> "VALID".equals(r.getStatus())).count();
        long duplicatedCount = results.stream().filter(r -> "DUPLICATED".equals(r.getStatus())).count();

        assertThat(validCount).isEqualTo(1);
        assertThat(duplicatedCount).isEqualTo(49);
        verify(checkinRepository, times(1)).save(any(Checkin.class));
    }
}
