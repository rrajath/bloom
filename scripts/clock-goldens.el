;;; clock-goldens.el --- Regenerate Bloom's clocking goldens with Org itself  -*- lexical-binding: t; -*-

;; Bloom's clock edits (OrgMutations.clockIn / clockOut / cancelClock) are
;; tested byte for byte against what Org writes for the same edits.  Each
;; app/src/test/resources/golden/clock/NAME.org carries its edits in header
;; comments, which both this script and OrgClockGoldenTest read:
;;
;;   # options: remove-zero adapt-indentation
;;   # ops: in TITLE 2026-10-30T09:15 | out 2026-10-30T10:30 | cancel
;;
;; `in' clocks in on the heading titled TITLE (clocking out whatever runs
;; at the same time, as `org-clock-in' does), `out' clocks the running clock
;; out, `cancel' cancels it.  This script writes Org's result to NAME.expected.org.
;;
;; Not run in CI.  Rerun after adding a case, with a fixed zone and the C
;; locale so day names and the DST case are reproducible:
;;
;;   TZ=America/New_York LC_ALL=C emacs -Q --batch \
;;     -L /path/to/org/lisp -l scripts/clock-goldens.el
;;
;; Last generated with Org 9.8.10.

(require 'org)
(require 'org-clock)

(setq org-clock-persist nil
      org-clock-auto-clock-resolution nil
      org-clock-in-resume nil
      org-log-into-drawer t
      org-clock-into-drawer t
      org-todo-keywords '((sequence "TODO" "NEXT" "|" "DONE")))

(defconst clock-goldens-dir
  (expand-file-name "../app/src/test/resources/golden/clock/"
                    (file-name-directory (or load-file-name buffer-file-name))))

(defun clock-goldens--time (s)
  "Encode S, as `2026-10-30T09:15', in local time."
  (unless (string-match "\\`\\([0-9]+\\)-\\([0-9]+\\)-\\([0-9]+\\)T\\([0-9]+\\):\\([0-9]+\\)\\'" s)
    (error "Bad time %S" s))
  (let ((n (lambda (i) (string-to-number (match-string i s)))))
    (encode-time (list 0 (funcall n 5) (funcall n 4) (funcall n 3) (funcall n 2) (funcall n 1) nil -1 nil))))

(defun clock-goldens--header (key)
  "The value of the `# KEY:' header comment in the current buffer, or nil."
  (save-excursion
    (goto-char (point-min))
    (when (re-search-forward (format "^# %s: \\(.*\\)$" key) nil t)
      (match-string-no-properties 1))))

(defun clock-goldens--run (op)
  (pcase (split-string op)
    (`("in" ,title ,time)
     ;; `org-clock-in' would close a running clock at the real current time;
     ;; close it at TIME instead, which is the same minute in actual use.
     (when (org-clocking-p) (org-clock-out nil t (clock-goldens--time time)))
     (goto-char (or (org-find-exact-headline-in-buffer title) (error "No heading %S" title)))
     (org-clock-in nil (clock-goldens--time time)))
    (`("out" ,time) (org-clock-out nil t (clock-goldens--time time)))
    (`("cancel") (org-clock-cancel))
    (_ (error "Bad op %S" op))))

(dolist (file (directory-files clock-goldens-dir t "\\`[^.]+\\.org\\'"))
  (with-temp-buffer
    (insert-file-contents file)
    (let* ((options (split-string (or (clock-goldens--header "options") "")))
           (ops (split-string (or (clock-goldens--header "ops") "") "|" t "[ \t]+"))
           (org-adapt-indentation (and (member "adapt-indentation" options) t))
           (org-clock-out-remove-zero-time-clocks (and (member "remove-zero" options) t)))
      (org-mode)
      (mapc #'clock-goldens--run ops)
      ;; A clock left running here must not leak into the next file.
      (move-marker org-clock-marker nil)
      (move-marker org-clock-hd-marker nil)
      (setq org-clock-current-task nil)
      (let ((coding-system-for-write 'utf-8-unix))
        (write-region nil nil (concat (file-name-sans-extension file) ".expected.org"))))))

;;; clock-goldens.el ends here
